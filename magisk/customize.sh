#!/system/bin/sh
# Open Headunit, CoD OS placeholder: a system app (android.uid.system) in /system/priv-app.
# Magisk extracts the module files first (on_install) and then calls set_permissions below.

ui_print "- Open Headunit (CoD OS, system app, no root)"
ui_print "- package com.andrerinas.headunitrevived, uid android.uid.system"
ui_print "- A copy of the same package installed from /data/app with another signer"
ui_print "  must be removed first, or the system copy is not used."

set_permissions() {
  for d in system system/priv-app system/priv-app/OpenHeadunit system/etc system/etc/permissions; do
    set_perm "$MODPATH/$d" 0 0 0755 u:object_r:system_file:s0
  done
  set_perm "$MODPATH/system/priv-app/OpenHeadunit/OpenHeadunit.apk" 0 0 0644 u:object_r:system_file:s0
  set_perm "$MODPATH/system/etc/permissions/privapp-permissions-openheadunit.xml" 0 0 0644 u:object_r:system_file:s0
}
