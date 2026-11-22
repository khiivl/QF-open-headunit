#!/system/bin/sh
# Starts the zLink tee proxy and redirects the zLink daemon's connections to it.
# Run as root on the head unit after boot, then restart the Bluetooth daemon child and z-link once.
#   proxy.dex  : built from Proxy.java (javac --release 8, then d8), at /data/local/tmp/proxy.dex
CLASSPATH=/data/local/tmp/proxy.dex setsid nohup su 2000 -c 'exec app_process /system/bin Proxy' \
  > /data/local/tmp/proxy.log 2>&1 < /dev/null &
sleep 3
for p in 1666 1777 1888 1999; do
  iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport $p -m owner --uid-owner 0 -j REDIRECT --to-ports 1$p
done
