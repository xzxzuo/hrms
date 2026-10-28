import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
const mode=__ENV.MODE||'cached';
const base=__ENV.BASE_URL||'http://app:8080';
const latency=new Trend('measured_latency',true);
const begins=new Trend('measurement_begin_epoch_ms');
const ends=new Trend('measurement_end_epoch_ms');
const unexpected=new Rate('unexpected_failure');
const accepted=new Counter('accepted');
const rejected=new Counter('expected_rejection');
const scenario=mode==='seckill'?{executor:'per-vu-iterations',vus:1000,iterations:1,maxDuration:'120s'}:mode==='booking'?{executor:'per-vu-iterations',vus:20,iterations:5,maxDuration:'120s'}:mode==='rate'?{executor:'shared-iterations',vus:1,iterations:1,maxDuration:'30s'}:{executor:'constant-vus',vus:20,duration:'10s'};
export const options={scenarios:{measured:scenario},setupTimeout:'10m',summaryTrendStats:['avg','min','med','max','p(95)','p(99)'],thresholds:{unexpected_failure:['rate==0']}};
function headers(token){const h={'Content-Type':'application/json'};if(token)h.Authorization=`Bearer ${token}`;return h;}
function post(path,body,token){return http.post(base+path,JSON.stringify(body),{headers:headers(token)});}
function checked(response){if(response.status!==200)throw new Error(`${response.url}: ${response.status} ${response.body}`);return response.json();}
function date(offset){return new Date(Date.now()+offset*86400000).toISOString().slice(0,10);}
export function setup(){
 if(mode==='cached'||mode==='baseline')return {hotel:1};
 const admin=checked(post('/api/auth/login',{email:'admin@hrms.local',password:__ENV.DEMO_PASSWORD})).accessToken;
 const run=Date.now().toString();
 const hotel=checked(post('/api/admin/hotels',{name:`Benchmark ${run}`,city:'LoadTest',address:'Synthetic fixture',timezone:'UTC',facilities:[],images:[]},admin)).id;
 checked(http.put(`${base}/api/admin/hotels/${hotel}/status`,JSON.stringify({status:'OPEN'}),{headers:headers(admin)}));
 const stock=mode==='booking'?1000:100;
 const room=checked(post(`/api/admin/hotels/${hotel}/room-types`,{name:'Benchmark',bedType:'Queen',capacity:2,basePrice:500,roomCount:stock,status:'ACTIVE'},admin)).id;
 for(let d=5;d<8;d++){
  checked(http.put(`${base}/api/admin/room-types/${room}/inventory`,JSON.stringify({date:date(d),total:stock,blocked:0,saleStatus:'OPEN'}),{headers:headers(admin)}));
  checked(http.put(`${base}/api/admin/room-types/${room}/rates`,JSON.stringify({date:date(d),price:500}),{headers:headers(admin)}));
 }
 const count=mode==='seckill'?1000:mode==='rate'?1:20;
 const tokens=[];
 // Account provisioning is setup, excluded from measured_latency and phase request counts.
 for(let start=0;start<count;start+=10){
  const credentials=[];for(let i=start;i<Math.min(start+10,count);i++)credentials.push({email:`load-${run}-${i}@test.local`,password:'Load-test-password-123'});
  const registrations=http.batch(credentials.map(c=>['POST',base+'/api/auth/register',JSON.stringify(c),{headers:headers('')}]));registrations.forEach(checked);
  const logins=http.batch(credentials.map(c=>['POST',base+'/api/auth/login',JSON.stringify(c),{headers:headers('')}]));logins.forEach(r=>tokens.push(checked(r).accessToken));
 }
 const campaigns=[];const n=mode==='rate'?12:mode==='seckill'?1:0;
 for(let i=0;i<n;i++)campaigns.push(checked(post(`/api/admin/hotels/${hotel}/campaigns`,{roomTypeId:room,checkIn:date(5),checkOut:date(8),startsAt:new Date(Date.now()-60000).toISOString(),endsAt:new Date(Date.now()+3600000).toISOString(),price:99,quota:100},admin)).id);
 return {admin,hotel,room,tokens,campaigns,run,in:date(5),out:date(8)};
}
function measure(r,expected){latency.add(r.timings.duration+r.timings.blocked);const ok=expected.includes(r.status);unexpected.add(!ok);if(!ok)console.warn(`UNEXPECTED status=${r.status} code=${r.body}`);check(r,{'expected response':()=>ok});if(r.status===200)accepted.add(1);else rejected.add(1);}
export default function(data){begins.add(Date.now());try{run(data);}finally{ends.add(Date.now());}}
function run(data){
 if(mode==='cached'||mode==='baseline'){measure(http.get(`${base}/api/hotels/${data.hotel}`),[200]);return;}
 if(mode==='booking'){
  const token=data.tokens[(__VU-1)%data.tokens.length];const q=post('/api/quotes',{roomTypeId:data.room,checkIn:data.in,checkOut:data.out,quantity:1},token);if(q.status!==200){measure(q,[200]);return;}
  measure(post('/api/bookings',{quoteId:q.json().quoteId,clientRequestId:`load-${data.run}-${__VU}-${__ITER}`},token),[200]);return;
 }
 if(mode==='seckill'){measure(post(`/api/seckill/${data.campaigns[0]}/requests`,{},data.tokens[__VU-1]),[200,409]);return;}
 for(const campaign of data.campaigns)measure(post(`/api/seckill/${campaign}/requests`,{},data.tokens[0]),[200,429]);
}
export function teardown(data){
 if(!data.admin)return;
 // Wait for asynchronous results; never treat a queued response as a successful booking.
 if(mode==='seckill'){
  let report;for(let i=0;i<30;i++){report=checked(http.get(`${base}/api/admin/hotels/${data.hotel}/bookings`,{headers:headers(data.admin)}));if(report.length===100)break;sleep(1);}
  const calendar=checked(http.get(`${base}/api/room-types/${data.room}/calendar?checkIn=${data.in}&checkOut=${data.out}`));
  if(report.length!==100||calendar.some(n=>n.held+n.sold>100||n.available<0))throw new Error('Seckill invariant failed');
  console.log(`INVARIANT successfulOrders=${report.length} campaign=${data.campaigns[0]} threeNightStock=100`);
 }
 const state=checked(http.get(`${base}/api/admin/reconciliation`,{headers:headers(data.admin)}));
 if(state.inventoryMismatches.length||state.campaignMismatches.length)throw new Error('Reconciliation mismatch');
 console.log(`FIXTURE hotel=${data.hotel} room=${data.room} campaign=${data.campaigns[0]||0}`);
}
export function handleSummary(data){return {[`/results/${mode}.json`]:JSON.stringify(data,null,2),stdout:JSON.stringify({mode,latency:data.metrics.measured_latency,unexpected:data.metrics.unexpected_failure,accepted:data.metrics.accepted,rejected:data.metrics.expected_rejection},null,2)+'\n'};}
