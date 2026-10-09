package fr.erick.threeai;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.mozilla.geckoview.*;

/** Browser UI and web engine are independent of Android System WebView. */
public final class BrowserActivity extends Activity {
    private static GeckoRuntime runtime;
    private static final class State {
        final ArrayList<GeckoSession> sessions = new ArrayList<>();
        String serialized, url="about:blank";
        boolean back, forward;
    }
    private State state;
    private GeckoView web;
    private EditText address;
    private TextView status;
    private Button forward;
    private boolean canBack;
    private SecretStore secrets;
    GeckoSession currentSession(){return state.sessions.get(state.sessions.size()-1);}
    GeckoView engineView(){return web;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setPadding(dp(4),0,dp(4),0);b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(v->action.run());return b;}
    private void row(LinearLayout root,Button... buttons){LinearLayout row=new LinearLayout(this);for(Button b:buttons)row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(row);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);secrets=new SecretStore(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(5,14,35));
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        row(root,button("3AI",this::finish),button("Retour",this::back),forward=button("Suivant",()->currentSession().goForward()),button("Recharger",()->currentSession().reload()));
        LinearLayout bar=new LinearLayout(this);address=new EditText(this);address.setSingleLine(true);address.setSaveEnabled(false);address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);address.setTextColor(Color.WHITE);address.setHintTextColor(Color.LTGRAY);address.setHint("Adresse du site");bar.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));bar.addView(button("Aller",()->navigate(address.getText().toString())));root.addView(bar);
        row(root,button("Clés Ollama",()->navigate("https://ollama.com/settings/keys")),button("Alibaba",()->navigate("https://console.alibabacloud.com/")));
        status=new TextView(this);status.setTextColor(Color.LTGRAY);status.setText("GeckoView · navigateur intégré");status.setPadding(dp(8),dp(3),dp(8),dp(3));root.addView(status);
        web=new GeckoView(this);web.setSaveEnabled(false);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        address.setOnEditorActionListener((v,id,event)->{navigate(address.getText().toString());return true;});
        if(runtime==null)runtime=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().javaScriptEnabled(true).remoteDebuggingEnabled(false).consoleOutput(false).build());
        Object retained=getLastNonConfigurationInstance();state=retained instanceof State?(State)retained:new State();
        boolean fresh=state.sessions.isEmpty();
        if(fresh){GeckoSession s=new GeckoSession();state.sessions.add(s);s.open(runtime);}
        for(GeckoSession s:state.sessions)delegates(s);web.setSession(currentSession());
        address.setText(state.url);canBack=state.back;forward.setEnabled(state.forward);
        if(fresh){
            String target=getIntent().getStringExtra("url");
            if(target!=null)navigate(target);
            else{try{String serialized=secrets.get("browser_session");GeckoSession.SessionState restore=serialized.isEmpty()?null:GeckoSession.SessionState.fromString(serialized);if(restore!=null){state.serialized=serialized;currentSession().restoreState(restore);}else currentSession().loadUri("about:blank");}catch(Exception e){status.setText("Session précédente illisible; copie conservée.");currentSession().loadUri("about:blank");}}
        }
    }
    static boolean webAddress(String value){String scheme=Uri.parse(value).getScheme();return "https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)||"about:blank".equals(value);}
    void navigate(String value){String target=value.trim();if(target.isEmpty())return;if(!target.contains(":"))target="https://"+target;if(!webAddress(target)){status.setText("Entrer une adresse de site HTTP ou HTTPS.");return;}address.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);web.requestFocus();currentSession().loadUri(target);}
    private void delegates(GeckoSession s){
        s.setContentDelegate(new GeckoSession.ContentDelegate(){@Override public void onTitleChange(GeckoSession session,String title){if(session==currentSession())status.setText(title+" · GeckoView");}@Override public void onCloseRequest(GeckoSession session){if(session==currentSession())closePopup();}});
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public void onLocationChange(GeckoSession session,String url,List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean gesture){if(session==currentSession()&&url!=null){state.url=url;address.setText(url);}}
            @Override public void onCanGoBack(GeckoSession session,boolean possible){if(session==currentSession()){canBack=possible;state.back=possible;}}
            @Override public void onCanGoForward(GeckoSession session,boolean possible){if(session==currentSession()){forward.setEnabled(possible);state.forward=possible;}}
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,LoadRequest request){return GeckoResult.fromValue(webAddress(request.uri)?AllowOrDeny.ALLOW:AllowOrDeny.DENY);}
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession parent,String uri){
                if(!webAddress(uri)||state.sessions.size()>=8)return null;
                GeckoSession popup=new GeckoSession();delegates(popup);state.sessions.add(popup);canBack=false;forward.setEnabled(false);
                new Handler(getMainLooper()).post(()->{if(!isDestroyed()&&popup.isOpen()){web.releaseSession();web.setSession(popup);}});
                return GeckoResult.fromValue(popup);
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession session,String uri,WebRequestError error){if(session==currentSession())status.setText("Chargement impossible · erreur Gecko "+error.code);return null;}
        });
        s.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onPageStart(GeckoSession session,String url){if(session==currentSession())status.setText("Chargement · GeckoView");}
            @Override public void onPageStop(GeckoSession session,boolean success){if(session==currentSession())status.setText(success?"Page chargée · GeckoView":"Chargement interrompu · GeckoView");}
            @Override public void onSessionStateChange(GeckoSession session,GeckoSession.SessionState snapshot){if(session==currentSession()){state.serialized=snapshot.toString();persistSession();}}
        });
        s.setPromptDelegate(new GeckoSession.PromptDelegate(){
            @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session,AlertPrompt prompt){GeckoResult<PromptResponse> answer=new GeckoResult<>();new AlertDialog.Builder(BrowserActivity.this).setTitle("Message du site").setMessage(prompt.message).setPositiveButton("OK",(d,w)->answer.complete(prompt.dismiss())).setOnCancelListener(d->answer.complete(prompt.dismiss())).show();return answer;}
            @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session,ButtonPrompt prompt){GeckoResult<PromptResponse> answer=new GeckoResult<>();new AlertDialog.Builder(BrowserActivity.this).setTitle("Confirmation du site").setMessage(prompt.message).setPositiveButton("OK",(d,w)->answer.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))).setNegativeButton("Annuler",(d,w)->answer.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))).setOnCancelListener(d->answer.complete(prompt.dismiss())).show();return answer;}
            @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session,TextPrompt prompt){GeckoResult<PromptResponse> answer=new GeckoResult<>();EditText input=new EditText(BrowserActivity.this);input.setText(prompt.defaultValue);new AlertDialog.Builder(BrowserActivity.this).setTitle("Saisie du site").setMessage(prompt.message).setView(input).setPositiveButton("OK",(d,w)->answer.complete(prompt.confirm(input.getText().toString()))).setNegativeButton("Annuler",(d,w)->answer.complete(prompt.dismiss())).setOnCancelListener(d->answer.complete(prompt.dismiss())).show();return answer;}
        });
    }
    private void persistSession(){if(state.serialized==null||state.serialized.length()>2097152)return;try{secrets.set("browser_session",state.serialized);}catch(Exception e){status.setText("Page ouverte; conservation de session impossible.");}}
    private void closePopup(){if(state.sessions.size()<=1){finish();return;}web.releaseSession();GeckoSession old=state.sessions.remove(state.sessions.size()-1);old.close();web.setSession(currentSession());canBack=false;currentSession().setActive(true);currentSession().flushSessionState();}
    private void back(){if(canBack)currentSession().goBack();else if(state.sessions.size()>1)closePopup();else finish();}
    @Override public void onBackPressed(){back();}
    @Override public Object onRetainNonConfigurationInstance(){return state;}
    @Override protected void onResume(){super.onResume();if(state!=null)currentSession().setActive(true);}
    @Override protected void onPause(){if(state!=null){currentSession().setActive(false);persistSession();}super.onPause();}
    @Override protected void onDestroy(){if(web!=null)web.releaseSession();if(state!=null){for(GeckoSession s:state.sessions){s.setContentDelegate(null);s.setNavigationDelegate(null);s.setProgressDelegate(null);s.setPromptDelegate(null);if(!isChangingConfigurations())s.close();}}super.onDestroy();}
}
