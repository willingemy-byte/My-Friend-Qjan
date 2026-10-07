package fr.erick.journallocal;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.security.MessageDigest;
final class FounderUpgrade {
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
 static synchronized void download(Context c)throws Exception{
  JSONObject release=LicenseClient.release(c);String url=release.getString("url");
  URI parsed=new URI(url),origin=new URI(EditionConfig.APK_ORIGIN);
  if(!"https".equals(parsed.getScheme())||parsed.getUserInfo()!=null||!parsed.getHost().equals(origin.getHost())||parsed.getPort()!=origin.getPort())throw new SecurityException("Source APK non officielle");
  File dir=new File(c.getCacheDir(),"founder-upgrade");dir.mkdirs();File target=new File(dir,"AIV-FOUNDER.apk");
  HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();h.setInstanceFollowRedirects(false);h.setConnectTimeout(15000);h.setReadTimeout(60000);MessageDigest hash=MessageDigest.getInstance("SHA-256");
  try{if(h.getResponseCode()!=200)throw new IOException("Téléchargement APK refusé");long total=0;try(InputStream in=h.getInputStream();FileOutputStream out=new FileOutputStream(target)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){total+=n;if(total>100_000_000)throw new IOException("APK trop volumineux");out.write(b,0,n);hash.update(b,0,n);}out.getFD().sync();}
   if(!hex(hash.digest()).equalsIgnoreCase(release.getString("sha256")))throw new SecurityException("SHA-256 APK différent");
   com.android.apksig.ApkVerifier.Result verification=new com.android.apksig.ApkVerifier.Builder(target).build().verify();
   if(!verification.isVerified()||verification.getSignerCertificates().size()!=1||!hex(MessageDigest.getInstance("SHA-256").digest(verification.getSignerCertificates().get(0).getEncoded())).equalsIgnoreCase(EditionConfig.APK_CERTIFICATE))throw new SecurityException("Signature cryptographique APK invalide");
   PackageManager pm=c.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;PackageInfo apk=pm.getPackageArchiveInfo(target.getAbsolutePath(),flags),installed=pm.getPackageInfo(c.getPackageName(),flags);
   if(apk==null||!c.getPackageName().equals(apk.packageName)||version(apk)<=version(installed)||version(apk)!=release.getLong("version_code"))throw new SecurityException("Package ou version APK incompatible");
   android.content.pm.Signature[] signers=Build.VERSION.SDK_INT>=28?apk.signingInfo.getApkContentsSigners():apk.signatures;
   android.content.pm.Signature[] current=Build.VERSION.SDK_INT>=28?installed.signingInfo.getApkContentsSigners():installed.signatures;
   if(signers.length!=1||current.length!=1||!signers[0].equals(current[0])||!hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())).equalsIgnoreCase(EditionConfig.APK_CERTIFICATE))throw new SecurityException("Signature APK différente");
   Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.allinvisible.aiv.upgrade/AIV-FOUNDER.apk"),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
   c.startActivity(intent);
  }catch(Exception e){target.delete();throw e;}finally{h.disconnect();}
 }
 static long version(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
}
