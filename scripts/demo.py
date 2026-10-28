"""Create isolated synthetic demo data and execute the complete REST business flow."""
import datetime as dt,json,os,time,uuid,urllib.request,urllib.error
base=os.environ.get('BASE_URL','http://localhost:8088');run=uuid.uuid4().hex[:10]
def api(method,path,body=None,token=None,expected=200):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=15) as r:status=r.status;result=json.load(r)
 except urllib.error.HTTPError as exc:status=exc.code;result=json.load(exc)
 assert status==expected,(path,status,result)
 return result
admin=api('POST','/api/auth/login',{'email':'admin@hrms.local','password':os.environ['DEMO_PASSWORD']})['accessToken']
hotel=api('POST','/api/admin/hotels',{'name':'HTTP Demo '+run,'city':'Demo','address':'Synthetic Street','timezone':'UTC','facilities':['WiFi'],'images':[]},admin)['id']
api('PUT',f'/api/admin/hotels/{hotel}/status',{'status':'OPEN'},admin)
room=api('POST',f'/api/admin/hotels/{hotel}/room-types',{'name':'Standard','bedType':'Queen','capacity':2,'basePrice':500,'roomCount':1,'status':'ACTIVE'},admin)['id']
physical=api('POST',f'/api/admin/room-types/{room}/rooms',{'roomNo':'101','status':'AVAILABLE'},admin)['id']
today=dt.datetime.now(dt.timezone.utc).date();tomorrow=today+dt.timedelta(days=1)
for i in range(3):
 date=(today+dt.timedelta(days=i)).isoformat()
 api('PUT',f'/api/admin/room-types/{room}/inventory',{'date':date,'total':1,'blocked':0,'saleStatus':'OPEN'},admin)
 api('PUT',f'/api/admin/room-types/{room}/rates',{'date':date,'price':500},admin)
credentials={'email':'demo-'+run+'@test.local','password':'Demo-user-password-123'}
api('POST','/api/auth/register',credentials)
user=api('POST','/api/auth/login',credentials)['accessToken']
def book(start,end,key,coupon=None):
 quote=api('POST','/api/quotes',{'roomTypeId':room,'checkIn':str(start),'checkOut':str(end),'quantity':1},user)['quoteId']
 command={'quoteId':quote,'clientRequestId':key}
 if coupon:command['couponId']=coupon
 order=api('POST','/api/bookings',command,user)
 assert api('POST','/api/bookings',command,user)['id']==order['id']
 return order['id']
# Confirm -> allocate physical room -> checkout; consumed historical sold is retained.
stay=book(today,tomorrow,'stay-'+run)
assert api('POST','/api/payment/simulate',{'bookingId':stay,'providerRef':'stay-'+run,'success':True},user)['status']=='CONFIRMED'
api('POST',f'/api/admin/bookings/{stay}/check-in',{},admin)
assert api('POST',f'/api/admin/bookings/{stay}/check-out',{},admin)['status']=='CHECKED_OUT'
api('POST',f'/api/bookings/{stay}/cancel',{},user,409)
# Coupon -> payment duplicate -> cancellation -> refund complete.
expires=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=1)).isoformat()
coupon=api('POST',f'/api/admin/hotels/{hotel}/coupons',{'name':'Demo discount','discount':100,'minTotal':200,'expiresAt':expires,'quota':1},admin)['id']
owned=api('POST',f'/api/coupons/{coupon}/claim',{},user)['id']
order=book(tomorrow,tomorrow+dt.timedelta(days=1),'refund-'+run,owned)
for _ in range(2):assert api('POST','/api/payment/simulate',{'bookingId':order,'providerRef':'refund-'+run,'success':True},user)['status']=='CONFIRMED'
for _ in range(2):assert api('POST',f'/api/bookings/{order}/cancel',{},user)['status']=='CANCELLED'
ledger=api('GET',f'/api/bookings/{order}/payments',token=user)
assert len(ledger['payments'])==1 and len(ledger['refunds'])==1
refund=ledger['refunds'][0]['id'];api('POST',f'/api/admin/refunds/{refund}/complete',{},admin)
# Asynchronous seckill creates a real order, then cancellation releases dates without returning quota.
now=dt.datetime.now(dt.timezone.utc)
campaign=api('POST',f'/api/admin/hotels/{hotel}/campaigns',{'roomTypeId':room,'checkIn':str(tomorrow),'checkOut':str(tomorrow+dt.timedelta(days=1)),'startsAt':(now-dt.timedelta(minutes=1)).isoformat(),'endsAt':(now+dt.timedelta(hours=1)).isoformat(),'price':99,'quota':1},admin)['id']
request=api('POST',f'/api/seckill/{campaign}/requests',{},user)
for _ in range(30):
 result=api('GET','/api/seckill/requests/'+request['id'],token=user)
 if result['status']!='QUEUED':break
 time.sleep(.5)
assert result['status']=='SUCCESS',result
assert api('POST',f'/api/seckill/{campaign}/requests',{},user)['id']==request['id']
api('POST',f'/api/bookings/{result["booking_id"]}/cancel',{},user)
check=api('GET','/api/admin/reconciliation',token=admin)
assert not check['inventoryMismatches'] and not check['campaignMismatches'],check
print(json.dumps({'hotelId':hotel,'roomTypeId':room,'physicalRoomId':physical,'checkedOutBooking':stay,'refundedBooking':order,'refundId':refund,'seckillBooking':result['booking_id'],'campaignId':campaign,'reconciliation':'consistent','via':base},indent=2))
