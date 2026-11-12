import argparse,hashlib,pathlib,re,sys
sys.path.insert(0,str(pathlib.Path('app/scripts').resolve()))
import android_cloud_ui as ui
ui.PACKAGE='com.yfuse.validation'
class Validation(ui.Session):
 def adb(self,*a,**kw):
  a=tuple(s.replace(ui.PACKAGE+'/.MainActivity',ui.PACKAGE+'/com.yfuse.MainActivity') if isinstance(s,str) else s for s in a)
  return super().adb(*a,**kw)
 def verify_apk(self,path):
  apks=list(path.rglob('*.apk'));assert len(apks)==1;self.apk=apks[0]
  ui.command([ui.sdk_tool('apksigner'),'verify',str(self.apk)])
  badging=ui.command([ui.sdk_tool('aapt'),'dump','badging',str(self.apk)])
  assert "name='com.yfuse.validation'" in badging and "versionName='1.9.4'" in badging
  return {'build':'debug validation','sha256':hashlib.sha256(self.apk.read_bytes()).hexdigest()}
out=pathlib.Path('app-evidence');out.mkdir(exist_ok=True)
s=Validation(out,{'versionCode':346,'versionName':'1.9.4'},0,int(sys.argv[1]))
try:ui.run_cases(s,argparse.Namespace(apk_directory=pathlib.Path('app-apk'),soak_seconds=45,layout_probe=True))
finally:
 try:s.diagnostics()
 finally:s.restore();s.save()
