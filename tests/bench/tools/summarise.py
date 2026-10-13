import json,collections,csv,sys,re
R='tests/out/bench/reports/'
r=json.load(open(R+'large-colony.json')); l=json.load(open(R+'large-colony.layout.json'))
print(r['generated_at'],'fatal',r.get('fatal'),r.get('run'),'labor',r['environment'].get('labor'))
print('caveats',[c[:160] for c in r['caveats']])
print('staff',[(s['trade'],s['size']) for s in r['setup']['staffing']])
print('orders',[(o.get('name'),o.get('filed')) for o in r['setup']['orders']])
roster=r['setup']['roster']; lots={x['name']:x['box'] for x in l['lots']}; d=l['districts'][0]['box']
n=collections.Counter(); kinds=collections.Counter(); who=set(); tot=0; first=None
for row in csv.DictReader(open(R+'large-colony.work.csv')):
    if row['step']!='STARTED' or not row['worker']: continue
    x,z=int(row['site_x']),int(row['site_z']); tot+=1; k=row['kind'].split('/')[-1]
    if d['minX']<=x<=d['maxX'] and d['minZ']<=z<=d['maxZ']:
        kinds[k]+=1; who.add(row['worker'])
        if k=='StripNode' and first is None: first=row['game_time']
    for name,b in lots.items():
        if b['minX']-2<=x<=b['maxX']+2 and b['minZ']-2<=z<=b['maxZ']+2:
            n[name if not name.startswith(('lodge','house','paddock','garden')) else name.split('-')[0]]+=1; break
print('starts',tot,n.most_common(12))
print('quarry district',dict(kinds),'workers',len(who),collections.Counter(roster.get(w) for w in who).most_common(),'first strip',first)
mx=0; ser=[]
for i,line in enumerate(open(R+'large-colony.census.ndjson')):
    cc=json.loads(line); v=cc['standing'].get('riding',0); mx=max(mx,v)
    if i%60==0: ser.append((cc['game_time'],v,cc['standing'].get('stalled',0)))
print('max riding',mx,'(tick, riding, stalled)',ser)
c=(r.get('video') or {}).get('cut') or {}
print('cuts',[(x.get('spot') or 'overview') for x in c.get('cuts',[])])
log=open(R+'large-colony.server.log',errors='replace').read().split('\n')
u=[x for x in log if 'unassignedKinds=' in x]
for x in (u[len(u)//3],u[-1]):
    print(re.search(r'Oct2026 ([0-9:]+)',x).group(1), re.findall(r'[A-Z_]+/folkways:build/StripNode=\d+',x), re.findall(r'BEYOND_HORIZON/folkways:haul/TransferNode=\d+',x))
import statistics
rows=[int(x['full_ns'])/1e6 for x in csv.DictReader(open(R+'large-colony.ticks.csv')) if int(x['game_time'])>r['run']['start']]
print('mspt mean %.1f p95 %.1f'%(statistics.mean(rows),sorted(rows)[int(len(rows)*.95)]))
