#!/usr/bin/env python3
"""Build this Android app and its vendored native relay using a reviewed Android SDK and ECJ.
No dependency is downloaded by this build script. Signing secrets stay outside the project.
"""
import argparse, os, secrets, subprocess, zipfile, shutil, hashlib, json
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--android-jar',required=True,type=Path)
p.add_argument('--build-tools',required=True,type=Path)
p.add_argument('--ecj',required=True,type=Path)
native=p.add_mutually_exclusive_group(required=True)
native.add_argument('--ndk',type=Path)
native.add_argument('--reuse-native-apk',type=Path,help='Reuse only the pinned 0.2.0 native binaries with unchanged native sources.')
native.add_argument('--reuse-native-apk-06',type=Path,help='Reuse the pinned 0.6.0 native binaries only while all native sources match.')
signing=p.add_mutually_exclusive_group(required=True)
signing.add_argument('--signing-dir',type=Path)
signing.add_argument('--unsigned',action='store_true',help='Build and align an unsigned APK; no signing key is read or generated.')
a=p.parse_args()
reuse_apk=a.reuse_native_apk_06 or a.reuse_native_apk
root=Path(__file__).resolve().parent
build=root/'build';classes=build/'classes';dex=build/'dex'
for d in (classes,dex):
 if d.exists():shutil.rmtree(d)
for d in (build,classes,dex):d.mkdir(parents=True,exist_ok=True)
def run(*args,env=None):subprocess.run([str(x) for x in args],check=True,env=env)
run('python3',root/'tools/connect_reader.py')
vendor=root/'third_party/zdtun';cpp=root/'app/src/main/cpp'
clang=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/clang' if a.ndk else None
if reuse_apk:
 pin=json.loads((root/('tools/native-reuse-0.6.json' if a.reuse_native_apk_06 else 'tools/native-reuse.json')).read_text())
 assert hashlib.sha256(reuse_apk.read_bytes()).hexdigest()==pin['apk_sha256'],'Native APK differs from the pinned reviewed artifact'
 for filename,digest in pin['source_sha256'].items():
  assert hashlib.sha256((root/filename).read_bytes()).hexdigest()==digest,'Native source changed; rebuild using --ndk: '+filename
for abi,target in [('arm64-v8a','aarch64-linux-android26'),('x86_64','x86_64-linux-android26')]:
 out=build/'lib'/abi;out.mkdir(parents=True,exist_ok=True)
 if reuse_apk:
  with zipfile.ZipFile(reuse_apk) as z:
   for lib in ['libjournal_zdtun.so','libjournalrelay.so']:
    data=z.read('lib/'+abi+'/'+lib)
    assert hashlib.sha256(data).hexdigest()==pin['libraries_sha256'][abi+'/'+lib]
    (out/lib).write_bytes(data)
  continue
 common=[clang,'--target='+target,'-O2','-fPIC','-ffunction-sections','-fdata-sections','-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-D_LITTLE_ENDIAN','-DNO_DEBUG','-shared','-Wl,-z,relro,-z,now,-z,max-page-size=16384','-Wl,--gc-sections','-Wl,--no-undefined','-I'+str(vendor)]
 run(*common,'-Wl,-soname,libjournal_zdtun.so','-Wl,--version-script='+str(cpp/'zdtun.exports'),vendor/'zdtun.c',vendor/'utils.c','-o',out/'libjournal_zdtun.so')
 run(*common,'-fvisibility=hidden','-Wl,-soname,libjournalrelay.so',cpp/'relay.c',cpp/'tls_sni.c',cpp/'jni.c','-L'+str(out),'-ljournal_zdtun','-o',out/'libjournalrelay.so')
sources=sorted((root/'app/src/main/java').rglob('*.java'))
run('java','-jar',a.ecj,'-encoding','UTF-8','-source','1.8','-target','1.8','-bootclasspath',os.pathsep.join([str(a.android_jar),str(a.build_tools/'core-lambda-stubs.jar')]),'-warn:-deprecation','-d',classes,*sources)
run('java','-cp',a.build_tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',a.android_jar,'--output',dex,*sorted(classes.rglob('*.class')))
unsigned=build/'journal-local-unsigned.apk'
run(a.build_tools/'aapt2','link','--manifest',root/'app/src/main/AndroidManifest.xml','-I',a.android_jar,'-A',root/'app/src/main/assets','--min-sdk-version','26','--target-sdk-version','35','-o',unsigned)
with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
 for f in sorted(dex.glob('*.dex')):z.write(f,f.name)
 for f in sorted((build/'lib').rglob('*.so')):z.write(f,str(f.relative_to(build)))
aligned=build/'journal-local-aligned.apk';signed=build/'journal-local.apk'
run(a.build_tools/'zipalign','-f','-p','4',unsigned,aligned)
if a.unsigned:
 run(a.build_tools/'zipalign','-c','-P','16','4',aligned)
 print('UNSIGNED_APK:',aligned)
 raise SystemExit(0)
password_file=a.signing_dir/'password.txt';keystore=a.signing_dir/'journal-local.p12'
if not keystore.is_file() or not password_file.is_file():
 raise SystemExit('Update requires the existing journal-local.p12 and password.txt; no replacement signing key is generated.')
# Validate the existing certificate before signing; never substitute another APK key.
keytool=shutil.which('keytool') or '/usr/lib/jvm/java-17-openjdk-amd64/bin/keytool'
certificate=subprocess.check_output([keytool,'-exportcert','-keystore',str(keystore),'-alias','journal-local','-storepass:file',str(password_file)])
if hashlib.sha256(certificate).hexdigest() != '4a14b9e2cf3869fa9faf2317cba96e948145e4dba32240f9547380c040deba7b':
 raise SystemExit('Signing certificate differs from the recorded 0.5.0 certificate; update refused.')
run('java','-jar',a.build_tools/'lib/apksigner.jar','sign','--ks',keystore,'--ks-key-alias','journal-local','--ks-pass','file:'+str(password_file),'--out',signed,aligned)
run('java','-jar',a.build_tools/'lib/apksigner.jar','verify','--verbose',signed)
print('APK:',signed)