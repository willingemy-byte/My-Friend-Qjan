#!/usr/bin/env python3
"""Build AIV Android with reviewed Android SDK/NDK inputs.
No dependency is downloaded by this script. Signing secrets stay outside the project.
"""
import argparse, os, subprocess, zipfile, shutil, hashlib, json
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--android-jar',required=True,type=Path)
p.add_argument('--build-tools',required=True,type=Path)
p.add_argument('--shizuku-dir',required=True,type=Path)
native=p.add_mutually_exclusive_group(required=True)
native.add_argument('--ndk',type=Path)
native.add_argument('--reuse-native-apk',type=Path)
native.add_argument('--reuse-native-apk-06',type=Path)
signing=p.add_mutually_exclusive_group(required=True)
signing.add_argument('--signing-dir',type=Path)
signing.add_argument('--unsigned',action='store_true')
a=p.parse_args()

root=Path(__file__).resolve().parent
build=root/'build'
classes=build/'classes'
dex=build/'dex'
for d in (classes,dex):
    if d.exists():
        shutil.rmtree(d)
for d in (build,classes,dex):
    d.mkdir(parents=True,exist_ok=True)

def run(*args,env=None):
    subprocess.run([str(x) for x in args],check=True,env=env)

run('python3',root/'tools/generate_config.py')
run('python3',root/'tools/generate_access_policy.py')

reuse_apk=a.reuse_native_apk_06 or a.reuse_native_apk
vendor=root/'third_party/zdtun'
cpp=root/'app/src/main/cpp'
clang=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/clang' if a.ndk else None

if reuse_apk:
    pin=json.loads((root/('tools/native-reuse-0.6.json' if a.reuse_native_apk_06 else 'tools/native-reuse.json')).read_text())
    assert hashlib.sha256(reuse_apk.read_bytes()).hexdigest()==pin['apk_sha256']
    for filename,digest in pin['source_sha256'].items():
        assert hashlib.sha256((root/filename).read_bytes()).hexdigest()==digest, 'Native source changed: '+filename

for abi,target in [('arm64-v8a','aarch64-linux-android26'),('x86_64','x86_64-linux-android26')]:
    out=build/'lib'/abi
    out.mkdir(parents=True,exist_ok=True)
    if reuse_apk:
        with zipfile.ZipFile(reuse_apk) as z:
            for lib in ['libjournal_zdtun.so','libjournalrelay.so']:
                data=z.read('lib/'+abi+'/'+lib)
                assert hashlib.sha256(data).hexdigest()==pin['libraries_sha256'][abi+'/'+lib]
                (out/lib).write_bytes(data)
        continue
    common=[
        clang,'--target='+target,'-O2','-fPIC','-ffunction-sections','-fdata-sections',
        '-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-D_LITTLE_ENDIAN','-DNO_DEBUG',
        '-shared','-Wl,-z,relro,-z,now,-z,max-page-size=16384','-Wl,--gc-sections',
        '-Wl,--no-undefined','-I'+str(vendor)
    ]
    run(*common,'-Wl,-soname,libjournal_zdtun.so','-Wl,--version-script='+str(cpp/'zdtun.exports'),
        vendor/'zdtun.c',vendor/'utils.c','-o',out/'libjournal_zdtun.so')
    run(*common,'-fvisibility=hidden','-Wl,-soname,libjournalrelay.so',
        cpp/'relay.c',cpp/'tls_sni.c',cpp/'jni.c','-L'+str(out),'-ljournal_zdtun',
        '-o',out/'libjournalrelay.so')

sources=sorted((root/'app/src/main/java').rglob('*.java'))
depdir=build/'deps'
if depdir.exists(): shutil.rmtree(depdir)
depdir.mkdir(parents=True,exist_ok=True)
dep_jars=[]
for name in ['aidl-13.1.5.aar','shared-13.1.5.aar','api-13.1.5.aar','provider-13.1.5.aar']:
    aar=a.shizuku_dir/name
    if not aar.is_file(): raise SystemExit('Missing Shizuku dependency: '+str(aar))
    out=depdir/(name+'.jar')
    with zipfile.ZipFile(aar) as z:
        out.write_bytes(z.read('classes.jar'))
    dep_jars.append(out)
annotation=a.shizuku_dir/'annotation-1.3.0.jar'
if not annotation.is_file(): raise SystemExit('Missing AndroidX annotation dependency: '+str(annotation))
compile_cp=os.pathsep.join(str(x) for x in dep_jars+[annotation])
bootclasspath=os.pathsep.join([str(a.android_jar),str(a.build_tools/'core-lambda-stubs.jar')])
run('javac','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',bootclasspath,
    '-classpath',compile_cp,'-Xlint:-deprecation','-d',classes,*sources)

run('java','-cp',a.build_tools/'lib/d8.jar','com.android.tools.r8.D8',
    '--min-api','26','--lib',a.android_jar,'--output',dex,*sorted(classes.rglob('*.class')),*dep_jars)

resources=build/'resources.zip'
run(a.build_tools/'aapt2','compile','--dir',root/'app/src/main/res','-o',resources)

# AIV 1.2 runtime is native. Keep legacy HTML in the repository for design/history,
# but do not package any HTML into the Android application.
asset_src=root/'app/src/main/assets'
runtime_assets=build/'runtime-assets'
if runtime_assets.exists(): shutil.rmtree(runtime_assets)
runtime_assets.mkdir(parents=True,exist_ok=True)
for src in asset_src.rglob('*'):
    if not src.is_file() or src.suffix.lower()=='.html':
        continue
    dst=runtime_assets/src.relative_to(asset_src)
    dst.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(src,dst)

unsigned=build/'all-in-visible-unsigned.apk'
run(a.build_tools/'aapt2','link','--manifest',root/'app/src/main/AndroidManifest.xml',
    '-I',a.android_jar,'-A',runtime_assets,'--min-sdk-version','26',
    '--target-sdk-version','35','-o',unsigned,resources)

with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
    for f in sorted(dex.glob('*.dex')):
        z.write(f,f.name)
    for f in sorted((build/'lib').rglob('*.so')):
        z.write(f,str(f.relative_to(build)))

aligned=build/'all-in-visible-aligned.apk'
signed=build/'all-in-visible.apk'
run(a.build_tools/'zipalign','-f','-p','4',unsigned,aligned)

if a.unsigned:
    run(a.build_tools/'zipalign','-c','-P','16','4',aligned)
    print('UNSIGNED_APK:',aligned)
    raise SystemExit(0)

password_file=a.signing_dir/'password.txt'
keystore=a.signing_dir/'all-in-visible.p12'
pin_file=a.signing_dir/'certificate.sha256'
alias='all-in-visible'
if not keystore.is_file() or not password_file.is_file() or not pin_file.is_file():
    raise SystemExit('AIV signing requires private all-in-visible.p12, password.txt and certificate.sha256 outside the repository.')

keytool=shutil.which('keytool') or '/usr/bin/keytool'
certificate=subprocess.check_output([
    keytool,'-exportcert','-keystore',str(keystore),'-alias',alias,
    '-storepass:file',str(password_file)
])
actual=hashlib.sha256(certificate).hexdigest()
expected=pin_file.read_text().strip().lower()
if actual!=expected:
    raise SystemExit('All In Visible signing certificate differs from the private recorded certificate.')

run('java','-jar',a.build_tools/'lib/apksigner.jar','sign',
    '--ks',keystore,'--ks-key-alias',alias,
    '--ks-pass','file:'+str(password_file),'--out',signed,aligned)
run('java','-jar',a.build_tools/'lib/apksigner.jar','verify','--verbose',signed)
print('APK:',signed)
