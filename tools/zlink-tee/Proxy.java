import java.io.*; import java.net.*; import java.util.*; import java.util.concurrent.*;
/** Transparent zLink daemon<->app proxy that also feeds frames to local tee clients on 127.0.0.1:17000. */
public class Proxy {
  static final int OFF = 10000, TEE_PORT = 17000;
  static final int CH_VIDEO = 1, CH_AUDIO = 2, CH_CTRL_D2A = 3, CH_CTRL_A2D = 4;
  static synchronized void L(String s){ System.out.println(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())+" "+s); System.out.flush(); }

  // ---- tee hub ----
  static final List<Client> clients = new CopyOnWriteArrayList<>();
  static final Object gopLock = new Object();
  static byte[] sps, pps; static ArrayList<byte[]> gop = new ArrayList<>(); static int gopBytes; static boolean gopValid;
  static volatile OutputStream daemonCtrlOut; static volatile Object daemonCtrlLock = new Object();

  static class Client {
    final Socket s; final BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(400);
    Client(Socket s){ this.s=s; }
    void send(byte[] b){ if (!q.offer(b)) { L("tee client too slow, dropping"); close(); } }
    void close(){ if (clients.remove(this)) onClientsChanged(false); try{s.close();}catch(Exception e){} }
  }
  static byte[] tee(int ch, byte[] frame) {
    byte[] o = new byte[5+frame.length]; o[0]=(byte)ch;
    int n=frame.length; o[1]=(byte)(n>>>24); o[2]=(byte)(n>>>16); o[3]=(byte)(n>>>8); o[4]=(byte)n;
    System.arraycopy(frame,0,o,5,n); return o;
  }
  static void publish(int port, boolean d2a, int type, byte[] frame) {
    int ch = 0;
    if (port==1888 && d2a) ch=CH_VIDEO; else if (port==1666 && d2a) ch=CH_AUDIO; else if (port==1777) ch = d2a?CH_CTRL_D2A:CH_CTRL_A2D;
    if (ch==0 || (clients.isEmpty() && ch!=CH_VIDEO)) return;
    byte[] t = tee(ch, frame);
    if (ch==CH_VIDEO) {
      synchronized (gopLock) {
        int nal = frame.length>36 ? frame[12+24]&31 : -1;
        if (nal==7) { sps=t; gopValid=false; }
        else if (nal==8) { pps=t; }
        else if (nal==5) { gop.clear(); gopBytes=0; if (sps!=null){gop.add(sps);} if(pps!=null){gop.add(pps);} gop.add(t); gopBytes=t.length; gopValid=true; }
        else if (gopValid) { gop.add(t); gopBytes+=t.length; if (gopBytes>(48<<20)) { gop.clear(); gopBytes=0; gopValid=false; } }
      }
    }
    for (Client c : clients) c.send(t);
  }
  static void serveTee() throws Exception {
    ServerSocket ss = new ServerSocket(); ss.setReuseAddress(true);
    ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), TEE_PORT));
    L("tee listening "+TEE_PORT);
    new Thread(() -> { while (true) { try { Socket s = ss.accept(); s.setTcpNoDelay(true); final Client c = new Client(s);
      L("tee client connected "+s.getPort());
      synchronized (gopLock) { if (gopValid) for (byte[] b : gop) c.q.offer(b); }
      // Marks the end of the replay: frames before it only rebuild the decoder's references and must not be drawn.
      c.q.offer(tee(5, new byte[]{-1,-1,-1,16,0,0,0,0,0,0,7,(byte)0xe0}));
      clients.add(c);
      onClientsChanged(true);
      new Thread(() -> { try { OutputStream o = s.getOutputStream(); while (true) { o.write(c.q.take()); } } catch (Throwable e) { c.close(); } }).start();
      new Thread(() -> { try { DataInputStream d = new DataInputStream(s.getInputStream());
        while (true) { int n = d.readInt(); if (n<12 || n>65536) throw new IOException("bad inject len "+n); byte[] f = new byte[n]; d.readFully(f); inject(f); }
      } catch (Throwable e) { c.close(); } }).start();
    } catch (Throwable e) { L("tee accept err "+e); } } }).start();
  }
  /** Writes one complete zLink frame toward the daemon on the control channel, between app frames. */
  static void inject(byte[] frame) throws IOException {
    OutputStream o = daemonCtrlOut; if (o==null) return;
    // only touch frames (type 0x111) may be injected
    int ty=((frame[8]&255)<<24)|((frame[9]&255)<<16)|((frame[10]&255)<<8)|(frame[11]&255);
    if ((ty!=0x111 && ty!=0x705) || frame[0]!=-1 || frame[1]!=-1 || frame[2]!=-1 || frame[3]!=16) return;
    synchronized (daemonCtrlLock) { o.write(frame); o.flush(); }
  }

  // ---- link-mode control: zLink's UI tells the daemon which link to offer (0x102, field 7: 2 = wireless CarPlay, 0 = none).
  // While a tee client is attached we hold the mode at 2 so the app being backgrounded cannot end the session.
  static volatile byte[] lastInitFrame;      // last 0x102 the app sent, unmodified
  static boolean mode(byte[] f) { return fieldValue(f, 7) == 2; }
  /** Value of a top-level varint field of a 0x102 frame, or -1. */
  static long fieldValue(byte[] f, int field) {
    int i = 12;
    while (i < f.length) {
      long tag = 0; int sh = 0; int b;
      do { b = f[i++] & 255; tag |= (long)(b & 127) << sh; sh += 7; } while ((b & 128) != 0 && i < f.length);
      int fn = (int)(tag >>> 3), wt = (int)(tag & 7);
      if (wt == 0) { long v = 0; sh = 0; do { b = f[i++] & 255; v |= (long)(b & 127) << sh; sh += 7; } while ((b & 128) != 0 && i < f.length); if (fn == field) return v; }
      else if (wt == 2) { long n = 0; sh = 0; do { b = f[i++] & 255; n |= (long)(b & 127) << sh; sh += 7; } while ((b & 128) != 0 && i < f.length); i += (int)n; }
      else return -1;
    }
    return -1;
  }
  /** Copy of a 0x102 frame with field 7 set to v (same length: the value is a one-byte varint). */
  static byte[] withMode(byte[] f, int v) {
    byte[] c = f.clone(); int i = 12;
    while (i < c.length) {
      long tag = 0; int sh = 0; int b;
      do { b = c[i++] & 255; tag |= (long)(b & 127) << sh; sh += 7; } while ((b & 128) != 0 && i < c.length);
      int fn = (int)(tag >>> 3), wt = (int)(tag & 7);
      if (wt == 0) { int start = i; do { b = c[i++] & 255; } while ((b & 128) != 0 && i < c.length); if (fn == 7 && i - start == 1) { c[start] = (byte) v; return c; } }
      else if (wt == 2) { long n = 0; sh = 0; do { b = c[i++] & 255; n |= (long)(b & 127) << sh; sh += 7; } while ((b & 128) != 0 && i < c.length); i += (int)n; }
      else return f;
    }
    return f;
  }
  static void writeToDaemon(byte[] frame) {
    OutputStream o = daemonCtrlOut; if (o == null || frame == null) return;
    try { synchronized (daemonCtrlLock) { o.write(frame); o.flush(); } } catch (IOException e) { L("daemon ctrl write failed: " + e); }
  }
  static void onClientsChanged(boolean added) {
    byte[] last = lastInitFrame; if (last == null) return;
    if (added && clients.size() == 1) { L("hold: offering wireless CarPlay (link_mode 2)"); writeToDaemon(withMode(last, 2)); }
    else if (!added && clients.isEmpty()) { L("hold released: restoring app's link_mode " + fieldValue(last, 7)); writeToDaemon(last); }
  }

  // ---- proxy ----
  public static void main(String[] a) throws Exception {
    serveTee();
    for (int p : new int[]{1666,1777,1888,1999}) { final int port=p;
      ServerSocket ss = new ServerSocket(); ss.setReuseAddress(true);
      ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port+OFF));
      L("proxy listening "+(port+OFF)+" -> "+port);
      new Thread(() -> { while (true) { try { final Socket d = ss.accept(); d.setTcpNoDelay(true);
        Socket u = new Socket();
        try { u.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000); }
        catch (IOException e) { try { u.close(); } catch (Exception x) {} try { d.close(); } catch (Exception x) {} throw e; }   // app not up: let the daemon retry
        u.setTcpNoDelay(true);
        L("pair "+port+" daemon:"+d.getPort());
        final Object lockToDaemon = new Object(), lockToApp = new Object();
        if (port==1777) { daemonCtrlOut = d.getOutputStream(); daemonCtrlLock = lockToDaemon; }
        new Thread(() -> pump(port, true, d, u, lockToApp)).start();
        new Thread(() -> pump(port, false, u, d, lockToDaemon)).start();
      } catch (Throwable e) { L("accept/connect err "+port+": "+e); try{Thread.sleep(500);}catch(Exception x){} } } }).start();
    }
    Thread.currentThread().join();
  }
  /** Frame-aware forwarder: forwards whole zLink frames so injected frames never split an app frame. */
  static void pump(int port, boolean d2a, Socket from, Socket to, Object outLock) {
    long total=0; int msgs=0;
    try {
      DataInputStream in = new DataInputStream(new BufferedInputStream(from.getInputStream(), 65536)); OutputStream out = to.getOutputStream();
      byte[] h = new byte[12];
      while (true) {
        in.readFully(h);
        if (h[0]!=-1||h[1]!=-1||h[2]!=-1||h[3]!=16) throw new IOException("bad magic");
        int len = ((h[4]&255)<<24)|((h[5]&255)<<16)|((h[6]&255)<<8)|(h[7]&255);
        if (len<0 || len>(8<<20)) throw new IOException("bad len "+len);
        int ty = ((h[8]&255)<<24)|((h[9]&255)<<16)|((h[10]&255)<<8)|(h[11]&255);
        byte[] f = new byte[12+len]; System.arraycopy(h,0,f,0,12); in.readFully(f,12,len);
        if (port==1777 && !d2a && ty==0x102) {
          lastInitFrame = f;
          if (!clients.isEmpty() && fieldValue(f, 7) != 2) { f = withMode(f, 2); L("hold: rewrote app link_mode to 2"); }
        }
        // OHU plays the CarPlay audio itself while attached; the zLink app must not play it a second time.
        boolean toApp = !(port==1666 && d2a && !clients.isEmpty());
        if (toApp) { synchronized (outLock) { out.write(f); out.flush(); } }
        total+=f.length; msgs++;
        publish(port, d2a, ty, f);
      }
    } catch (Throwable e) { L(port+(d2a?" d2a":" a2d")+" err "+e); }
    L(port+(d2a?" d2a":" a2d")+" closed, msgs "+msgs+" bytes "+total);
    if (port==1777 && !d2a) { /* leave daemonCtrlOut; stale writes fail harmlessly */ }
    try { from.close(); } catch(Exception e){} try { to.close(); } catch(Exception e){}
  }
}
