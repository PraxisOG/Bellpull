"""Disposable audit probes: F01/F05 assert repairs; F02/F04 still assert defects."""
from pathlib import Path
import subprocess, tempfile, json, time, os
from plugins.bellpull.planning import PlanningStore
from plugins.bellpull.artifacts import ArtifactError
from plugins.bellpull.coding_workspace import inspect_workspace
results={}
with tempfile.TemporaryDirectory(prefix='bellpull-audit-planning-') as name:
    store=PlanningStore(Path(name)); device='a'*32; now=int(time.time()*1000)
    body={'read_at_ms':now,'busy_only':True,'events':[]}
    store.calendar(device,body)
    for i in range(100):
        pid=f'{i:032x}'
        store.propose({'operation_id':pid,'device':device,'action':'create_event','title':'Audit fixture','start_ms':now+60000,'end_ms':now+120000})
        store.resolve({'id':pid,'device':device,'state':'rejected','result':'Audit fixture rejected'})
    saved=store.snapshot()['proposals']
    resolved=sum(p['state']=='rejected' for p in saved)
    pending=sum(p['state']=='pending' for p in saved)
    assert resolved==100 and pending==0
    try:
        store.propose({'operation_id':f'{100:032x}','device':device,'action':'create_event','title':'Audit fixture','start_ms':now+60000,'end_ms':now+120000})
        results['proposal_limit']='unexpectedly accepted'
    except ArtifactError as e: results['proposal_limit']={'resolved':resolved,'pending':pending,'error':str(e),'status':e.status}
    body['events']=[{'id':'1','start_ms':now+60000,'end_ms':now+120000,'all_day':False}]
    store.calendar(device,body); store.calendar(device,{'revoke':True})
    assert store.calendar(device) is None
    store.calendar(device,body)
    results['late_upload_after_revoke']={'restored':store.calendar(device) is not None,'events':len(store.calendar(device)['events'])}
with tempfile.TemporaryDirectory(prefix='bellpull-audit-git-') as name:
    root=Path(name)
    def git(*args): return subprocess.run(['git',*args],cwd=root,check=True,capture_output=True,text=True)
    git('init','-q'); git('config','user.email','audit@example.invalid'); git('config','user.name','Audit Fixture')
    (root/'.gitattributes').write_text('tracked.txt filter=audit\n'); (root/'tracked.txt').write_text('before\n')
    git('add','.'); git('commit','-qm','Fixture')
    git('config','filter.audit.clean','touch audit-filter-executed; cat')
    (root/'tracked.txt').write_text('after\n')
    inspect_workspace(str(root))
    results['workspace_clean_filter_executed']=(root/'audit-filter-executed').exists()
with tempfile.TemporaryDirectory(prefix='bellpull-audit-hardlink-') as name:
    base=Path(name); root=base/'workspace'; root.mkdir()
    def git(*args): return subprocess.run(['git',*args],cwd=root,check=True,capture_output=True,text=True)
    git('init','-q'); git('config','user.email','audit@example.invalid'); git('config','user.name','Audit Fixture')
    (root/'tracked.txt').write_text('fixture\n'); git('add','.'); git('commit','-qm','Fixture')
    outside=base/'outside.txt'; outside.write_text('AUDIT_OUTSIDE_WORKSPACE\n')
    os.link(outside,root/'linked.txt')
    listing=inspect_workspace(str(root))
    entry=next(f for f in listing['files'] if f['path']=='linked.txt')
    try:
        preview=inspect_workspace(str(root),entry['id'])
        results['workspace_hardlink']={'links':outside.stat().st_nlink,'outside_content_in_preview':'AUDIT_OUTSIDE_WORKSPACE' in preview['diff']}
    except ArtifactError as e:
        assert e.status==409 and 'Hard-linked files' in str(e)
        results['workspace_hardlink']={'links':outside.stat().st_nlink,'outside_content_in_preview':False,'error':str(e),'status':e.status}
print(json.dumps(results,indent=2))
assert results['proposal_limit']['status']==409
assert results['late_upload_after_revoke']['restored']
assert results['workspace_clean_filter_executed'] is False
assert results['workspace_hardlink']['outside_content_in_preview'] is False
