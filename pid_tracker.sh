#!/system/bin/sh
while true; do
  ts=`date +%T`
  pid=`pidof com.tivimatelite`
  echo "$ts pid=$pid"
  sleep 30
done
