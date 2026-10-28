"""Observe only this Compose project's load; no credentials or tokens are written."""
import json,signal,subprocess,sys,time,urllib.request
from pathlib import Path
mode=sys.argv[1]; running=True; samples=[]
def stop(*_):
 global running
 running=False
signal.signal(signal.SIGTERM,stop)
try:
 while running:
  row={'epochMs':int(time.time()*1000)}
  try:
   proc=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}','hrms-app-1','hrms-mysql-1','hrms-rabbitmq-1'],capture_output=True,text=True,timeout=8)
   row['containers']=[{k:v for k,v in json.loads(s).items() if k in ['Name','CPUPerc','MemUsage','MemPerc']} for s in proc.stdout.splitlines() if s]
   request=urllib.request.Request('http://localhost:8081/actuator/prometheus')
   metrics=urllib.request.urlopen(request,timeout=2).read().decode()
   row['gauges']=[s for s in metrics.splitlines() if s.startswith(('hikaricp_connections_active{','hikaricp_connections_pending{','hrms_outbox_backlog '))]
   sql="SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Questions','Innodb_row_lock_waits','Innodb_row_lock_time')"
   proc=subprocess.run(['docker','compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -e "$1"','sh',sql],capture_output=True,text=True,timeout=5)
   row['mysql']={s.split('\t')[0]:int(s.split('\t')[1]) for s in proc.stdout.splitlines() if '\t' in s}
  except Exception as exc:row['error']=type(exc).__name__
  samples.append(row)
  time.sleep(1)
finally:
 Path('results').mkdir(exist_ok=True)
 Path('results/'+mode+'-resources.json').write_text(json.dumps(samples,indent=2))
