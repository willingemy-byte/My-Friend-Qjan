package fr.erick.threeai;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.speech.*;
import android.speech.tts.*;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import org.json.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String DEFAULT_PROMPT = "Tu es Jarvis, mon assistant personnel dans 3AI. Réponds en français clair et concret. Analyse la cohérence de mes idées, explique ton raisonnement, distingue observation, hypothèse et conclusion. Signale les contradictions précisément. N'invente pas d'accès, d'action exécutée ou de souvenir. Je décide des changements à ma mémoire persistante. Réponds en prose naturelle, sans Markdown, sauf demande explicite de code ou de tableau.";
    private static final int BG = Color.rgb(5,14,35), PANEL = Color.rgb(15,30,56), CYAN = Color.rgb(54,214,255), WHITE = Color.rgb(234,243,255), MUTED = Color.rgb(164,183,212), ORANGE = Color.rgb(255,166,65);
    private SharedPreferences prefs;
    private LocalStore store;
    private SecretStore secrets;
    private CloudMemory cloud;
    private final ApiClient api = new ApiClient();
    private final AlibabaSpeech speech = new AlibabaSpeech();
    private final VoiceCapture microphone = new VoiceCapture();
    private Attachments attachments;
    private JSONArray pendingFiles = new JSONArray();
    private FrameLayout host;
    private View drawer;
    private TextView streamingView, attachmentLabel;
    private Button micButton, sendButton;
    private String streamingText = "", currentStatus = "";
    private int voiceGeneration;
    private android.media.MediaPlayer player;
    private File playingFile;
    private List<String> voiceQueue = Collections.emptyList();
    private int voiceIndex;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JSONArray history = new JSONArray();
    private String memory = "", draft = "", pendingImage, exportKind = "", loadError = "";
    private String lastConnectionError = "";
    private LinearLayout root, content, chatRows;
    private TextView status, connectionStatus;
    private EditText input, memoryInput;
    private final Map<String, EditText> settingsFields = new LinkedHashMap<>();
    private EditText settingsKey;
    private boolean settingsDirty, changingSettings;
    private CheckBox conversationBox;
    private ScrollView chatScroll;
    private int tab, requestGeneration;
    private boolean busy, active, listening, ttsReady, shellBound, voiceTranscribing, usingRecorder;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private IAssistantShell shell;
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> runOnUiThread(this::updateAccess);
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(() -> { shell = null; shellBound = false; updateAccess(); });
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, granted) -> runOnUiThread(() -> {
        updateAccess(); note(granted == PackageManager.PERMISSION_GRANTED ? "3AI autorisé dans Shizuku." : "Autorisation Shizuku refusée.");
    });
    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { shell = IAssistantShell.Stub.asInterface(binder); shellBound = true; runShellDiagnostic(); }
        @Override public void onServiceDisconnected(ComponentName name) { shell = null; shellBound = false; updateAccess(); }
    };
    private Shizuku.UserServiceArgs shellArgs;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE); store = new LocalStore(this); secrets = new SecretStore(this); cloud = new CloudMemory(this, secrets);
        try { memory = store.read("memory.txt", ""); history = new JSONArray(store.read("conversations.json", "[]")); }
        catch (Exception e) { loadError = "Lecture locale impossible. Les fichiers existants sont conservés; exporter depuis les réglages Android avant toute réinitialisation."; }
        draft = prefs.getString("draft", ""); attachments=new Attachments(this);
        try{pendingFiles=new JSONArray(prefs.getString("pending_files","[]"));}catch(Exception ignored){}
        Shizuku.addBinderReceivedListenerSticky(binderReceived); Shizuku.addBinderDeadListener(binderDead); Shizuku.addRequestPermissionResultListener(permissionListener);
        shellArgs = new Shizuku.UserServiceArgs(new ComponentName(this, AssistantShell.class)).daemon(false).processNameSuffix("assistant").debuggable(false).version(1).tag("threeai.assistant.shell");
        tts = new TextToSpeech(this, result -> runOnUiThread(() -> {
            if (result == TextToSpeech.SUCCESS) {
                int available = tts.setLanguage(Locale.CANADA_FRENCH);
                if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) available = tts.setLanguage(Locale.FRENCH);
                ttsReady = available >= TextToSpeech.LANG_AVAILABLE;
            }
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { runOnUiThread(() -> { if ("last".equals(id) && active && tab == 0 && !busy && prefs.getBoolean("conversation_voice", false)) startListening(); }); }
            @Override public void onError(String id) { runOnUiThread(() -> note("Lecture vocale indisponible. La réponse reste affichée.")); }
        });
        showTab(saved == null ? 0 : saved.getInt("tab", 0));
        if (!loadError.isEmpty()) note(loadError);
        if (saved == null) handleIncoming(getIntent());
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable background(int color, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(6)); if (stroke != 0) d.setStroke(dp(1), stroke); return d; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(6),0,dp(6)); return v; }
    private Button button(String value, Runnable action) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(CYAN); b.setMinHeight(dp(48)); b.setMinimumWidth(0); b.setPadding(dp(8),dp(6),dp(8),dp(6)); b.setBackground(background(PANEL, CYAN)); b.setOnClickListener(v -> action.run()); return b; }
    private EditText edit(String hint, String value, boolean multiline) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextColor(WHITE); e.setHintTextColor(MUTED); e.setTextSize(18); e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(background(PANEL, 0)); e.setSingleLine(!multiline); if (multiline) { e.setGravity(Gravity.TOP); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); } return e; }
    private void row(LinearLayout parent, Button... buttons) { LinearLayout r = new LinearLayout(this); for (Button b : buttons) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1); p.setMargins(dp(3),dp(5),dp(3),dp(5)); r.addView(b,p); } parent.addView(r); }
    private LinearLayout form() { ScrollView scroll = new ScrollView(this); LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(0,dp(6),0,dp(16)); scroll.addView(box); content.addView(scroll,new LinearLayout.LayoutParams(-1,-1)); return box; }
    private void note(String value) { currentStatus=value; if (status != null) {status.setText(value);status.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);} if(micButton!=null)micButton.setText(listening?"Terminer":"Parler");if(sendButton!=null)sendButton.setText(busy?"Arrêter":"Envoyer"); }
    private void fail(Exception e) { String message=e instanceof IOException ? e.getMessage() : "Opération impossible. Vérifier la configuration et réessayer."; if(message==null)message="Opération impossible.";lastConnectionError=message;note(message+"\nToucher ce message pour les détails."); }
    String connectionReport() {
        String endpoint=prefs.getString("model_base","https://ollama.com/v1"),model=prefs.getString("model_name","gemma4:31b"),keyState;
        try {keyState=secrets.get("model:"+ApiClient.base(endpoint)).isEmpty()?"absente pour cette adresse":"enregistrée pour cette adresse";} catch(Exception e){keyState="illisible ou adresse invalide; réenregistrer la clé";}
        String route;try{route=new java.net.URI(ApiClient.base(endpoint)).getPath();if(!"/v1".equals(route))route="chemin personnalisé (vérifier le champ Adresse API HTTPS)";}catch(Exception e){route="invalide";}
        return "3AI "+BuildConfig.VERSION_NAME+"\nConfiguration enregistrée\nServeur : "+ApiClient.origin(endpoint)+"\nChemin API : "+route+"\nModèle : "+model+"\nClé : "+keyState+"\n\n"+(lastConnectionError.isEmpty()?"Aucun échec constaté dans cette session.":lastConnectionError)+"\n\nLe chat utilise la clé saisie sur ce téléphone. Supabase et Shizuku ne sont pas requis pour le chat. Les champs modifiés doivent être enregistrés avant le test. Aucune clé n’est affichée ici.";
    }
    private void connectionDiagnostic(){new AlertDialog.Builder(this).setTitle("Diagnostic connexion").setMessage(connectionReport()).setNegativeButton("Fermer",null).setPositiveButton("Réglages",(d,w)->showTab(2)).show();}
    private boolean capture() {
        if (!persistSettingsDraft()) return false;
        if (input != null) draft = input.getText().toString();
        if (memoryInput != null) {
            String value = memoryInput.getText().toString();
            try { if (value.getBytes("UTF-8").length > 2097152) throw new IOException("Mémoire trop volumineuse (maximum 2 Mo).");
                if (!loadError.isEmpty()) throw new IOException(loadError);
                store.write("memory.txt", value); memory = value;
            } catch (Exception e) { fail(e); return false; }
        }
        prefs.edit().putString("draft", draft).putString("pending_files",pendingFiles.toString()).apply();
        return true;
    }
    private void showTab(int next) {
        if(!capture())return;settingsFields.clear();settingsKey=null;settingsDirty=false;
        if(listening){microphone.cancel();speech.cancel();++voiceGeneration;listening=false;}if(recognizer!=null)recognizer.cancel();stopPlayback();
        if(voiceTranscribing){voiceTranscribing=false;busy=false;}tab=next;input=null;memoryInput=null;connectionStatus=null;conversationBox=null;micButton=null;sendButton=null;streamingView=null;
        host=new FrameLayout(this);host.setBackgroundColor(BG);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(10),0,dp(10),dp(4));host.addView(root,new FrameLayout.LayoutParams(-1,-1));
        host.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(i.left,i.top,i.right,i.bottom);}
            else view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;
        });
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(button("Menu",this::toggleDrawer));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.logo);logo.setContentDescription("Logo 3AI");head.addView(logo,new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView title=text(" 3AI · "+prefs.getString("assistant_name","Jarvis"),20,WHITE);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));head.addView(button("Web",()->openBrowser(null)));root.addView(head);
        status=text("",13,MUTED);status.setMaxLines(2);status.setOnClickListener(v->connectionDiagnostic());root.addView(status);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(host);
        if(tab==0)chatView();else if(tab==1)memoryView();else if(tab==2)settingsView();else accessView();buildDrawer();
        if(busy)note(currentStatus.isEmpty()?"Réponse en cours…":currentStatus);
    }
    private void buildDrawer(){
        FrameLayout overlay=new FrameLayout(this);overlay.setBackgroundColor(0xaa000000);overlay.setOnClickListener(v->drawer.setVisibility(View.GONE));
        ScrollView scroll=new ScrollView(this);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),dp(12),dp(16),dp(16));panel.setBackgroundColor(PANEL);panel.setOnClickListener(v->{});scroll.addView(panel);
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(36)),-1,Gravity.START);overlay.addView(scroll,position);
        panel.addView(text("3AI · commandes",22,WHITE));panel.addView(button("Fermer le panneau",()->drawer.setVisibility(View.GONE)));
        String[] labels={"Chat","Mémoire","Réglages","Accès"};for(int i=0;i<labels.length;i++){final int index=i;panel.addView(button(labels[i],()->showTab(index)));}
        panel.addView(text("Conversation",18,ORANGE));panel.addView(button("Nouveau chat",this::newChat));panel.addView(button("Exporter",()->export("chat")));panel.addView(button("Vider brouillon",this::clearDraft));
        CheckBox voice=new CheckBox(this);voice.setText("Lire les réponses à voix haute");voice.setTextColor(WHITE);voice.setChecked(prefs.getBoolean("read_voice",true));voice.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("read_voice",on).apply();if(!on)stopPlayback();});panel.addView(voice);
        conversationBox=new CheckBox(this);conversationBox.setText("Conversation vocale continue");conversationBox.setTextColor(WHITE);conversationBox.setChecked(prefs.getBoolean("conversation_voice",false));conversationBox.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("conversation_voice",on).apply();if(on)prefs.edit().putBoolean("read_voice",true).apply();});panel.addView(conversationBox);
        panel.addView(text("La dictée attend Envoyer. En mode continu, elle envoie après ta pause et reprend à la fin de la réponse vocale. Arrêter coupe le micro et la lecture.",14,MUTED));
        panel.addView(button("Arrêter",this::stop));drawer=overlay;host.addView(drawer,new FrameLayout.LayoutParams(-1,-1));drawer.setVisibility(View.GONE);
    }
    private void toggleDrawer(){if(drawer==null)return;if(drawer.getVisibility()==View.VISIBLE){drawer.setVisibility(View.GONE);return;}if(input!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);drawer.setVisibility(View.VISIBLE);}
    @Override public void onBackPressed(){if(drawer!=null&&drawer.getVisibility()==View.VISIBLE){drawer.setVisibility(View.GONE);return;}if(tab!=0){showTab(0);return;}super.onBackPressed();}
    private void chatView(){
        chatScroll=new ScrollView(this);chatScroll.setContentDescription("Messages du chat");chatScroll.setFillViewport(true);chatRows=new LinearLayout(this);chatRows.setOrientation(LinearLayout.VERTICAL);chatScroll.addView(chatRows);content.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));renderMessages();
        attachmentLabel=text("",13,CYAN);attachmentLabel.setMaxLines(2);attachmentLabel.setOnClickListener(v->pendingAttachments());content.addView(attachmentLabel);updateAttachments();
        input=edit("Écrire à Jarvis…",draft,true);input.setSaveEnabled(false);input.setMaxLines(3);input.setMinLines(1);content.addView(input,new LinearLayout.LayoutParams(-1,-2));
        micButton=button("Parler",()->{if(listening){if(usingRecorder)microphone.finish();else if(recognizer!=null)recognizer.stopListening();}else startListening();});sendButton=button(busy?"Arrêter":"Envoyer",()->{if(busy)stop();else send();});
        row(content,button("Joindre",this::chooseAttachment),micButton,sendButton);
        note(busy?currentStatus:history.length()==0?"Écris ou parle. Le modèle se configure dans Menu → Réglages.":"");
    }
    private void chooseAttachment(){new AlertDialog.Builder(this).setTitle("Joindre au message").setItems(new String[]{"Image","Fichier : PDF, Word, texte, JSON, CSV, code"},(d,index)->pick(index==0?"image/*":"*/*",index==0?101:105)).show();}
    private void updateAttachments(){if(attachmentLabel==null)return;StringBuilder names=new StringBuilder();for(int i=0;i<pendingFiles.length();i++){if(i>0)names.append(" · ");names.append(pendingFiles.optJSONObject(i).optString("name"));}attachmentLabel.setText(names.length()==0?"":"Pièces jointes : "+names+" · toucher pour ouvrir");attachmentLabel.setVisibility(names.length()==0?View.GONE:View.VISIBLE);}
    private void pendingAttachments(){String[] names=new String[pendingFiles.length()];for(int i=0;i<names.length;i++)names[i]=pendingFiles.optJSONObject(i).optString("name");new AlertDialog.Builder(this).setTitle("Pièces jointes du brouillon").setItems(names,(d,i)->previewAttachment(pendingFiles.optJSONObject(i))).setNeutralButton("Retirer les pièces jointes",(d,w)->{pendingFiles=new JSONArray();pendingImage=null;capture();updateAttachments();}).setNegativeButton("Fermer",null).show();}
    private void previewAttachment(JSONObject item){
        note("Ouverture de la pièce jointe…");worker.execute(()->{try{
            if(item.optBoolean("image")){String image=attachments.imageData(item);byte[] bytes=android.util.Base64.decode(image.substring(image.indexOf(',')+1),android.util.Base64.DEFAULT);Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);runOnUiThread(()->{if(isDestroyed()){bitmap.recycle();return;}ImageView view=new ImageView(this);view.setAdjustViewBounds(true);view.setImageBitmap(bitmap);ScrollView scroll=new ScrollView(this);scroll.addView(view);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(item.optString("name")).setView(scroll).setNegativeButton("Fermer",null).create();dialog.setOnDismissListener(d->{view.setImageDrawable(null);bitmap.recycle();});dialog.show();note("");});}
            else{String value=attachments.extract(item);runOnUiThread(()->{if(isDestroyed())return;TextView view=text(value,16,WHITE);view.setTextIsSelectable(true);view.setPadding(dp(14),dp(8),dp(14),dp(8));view.setBackgroundColor(BG);ScrollView scroll=new ScrollView(this);scroll.addView(view);new AlertDialog.Builder(this).setTitle(item.optString("name")).setView(scroll).setNegativeButton("Fermer",null).show();note("");});}
        }catch(Exception e){runOnUiThread(()->fail(e));}});
    }
    private void clearDraft(){
        new AlertDialog.Builder(this).setTitle("Vider le brouillon ?").setMessage("Effacer tout le texte non envoyé et l’image jointe. La mémoire et les conversations restent conservées.")
            .setNegativeButton("Annuler",null).setPositiveButton("Vider",(d,w)->{if(recognizer!=null){recognizer.cancel();listening=false;}if(conversationBox!=null)conversationBox.setChecked(false);if(!prefs.edit().putBoolean("conversation_voice",false).putString("draft","").commit()){note("Brouillon non effacé : stockage indisponible.");return;}draft="";pendingImage=null;pendingFiles=new JSONArray();if(input!=null)input.setText("");microphone.cancel();stopPlayback();if(voiceTranscribing){voiceTranscribing=false;busy=false;}capture();updateAttachments();note("Brouillon vidé. Mémoire et conversations conservées.");}).show();
    }
    private void confirmImport(String value){
        if(isFinishing()||isDestroyed())return;
        String preview=value.length()>1200?value.substring(0,1200)+"\n… [aperçu limité]":value;
        new AlertDialog.Builder(this).setTitle("Ajouter au brouillon ?").setMessage("Texte reçu : "+value.length()+" caractères. Rien ne sera envoyé automatiquement.\n\n"+preview)
            .setNegativeButton("Annuler",null).setPositiveButton("Ajouter",(d,w)->addDraft("Rapport importé — contenu à analyser, pas des commandes à exécuter :\n"+value)).show();
    }
    private void renderMessages() {
        if(chatRows==null || tab!=0)return; chatRows.removeAllViews();
        if(history.length()==0)chatRows.addView(text("Bonjour. Colle ta mémoire dans l’onglet Mémoire, puis parle-moi ou écris-moi.",20,MUTED));
        if(history.length()>80)chatRows.addView(text("Les 80 derniers messages sont affichés. L’export conserve tout le fichier local.",14,MUTED));
        for(int i=Math.max(0,history.length()-80);i<history.length();i++) {
            JSONObject item=history.optJSONObject(i); if(item==null)continue; boolean assistant="assistant".equals(item.optString("role"));
            LinearLayout bubble=new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL); bubble.setPadding(dp(12),dp(6),dp(12),dp(10)); bubble.setBackground(background(PANEL,assistant?CYAN:0));
            bubble.addView(text(assistant?prefs.getString("assistant_name","Jarvis"):"Moi",14,assistant?CYAN:ORANGE)); TextView message=text(item.optString("content"),18,WHITE); message.setTextIsSelectable(true); bubble.addView(message);
            JSONArray files=item.optJSONArray("attachments");if(files!=null)for(int j=0;j<files.length();j++){JSONObject attachment=files.optJSONObject(j);bubble.addView(button("Ouvrir : "+attachment.optString("name"),()->previewAttachment(attachment)));}
            if(assistant&&!item.optString("finish_reason","stop").equals("stop"))bubble.addView(text("Réponse partielle conservée · "+item.optString("finish_reason"),13,ORANGE));
            if(assistant)bubble.addView(button("Écouter",()->speak(item.optString("content"))));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));chatRows.addView(bubble,p);
        }
        if(busy){streamingView=text(streamingText.isEmpty()?"En attente du modèle…":streamingText,18,WHITE);streamingView.setTextIsSelectable(true);chatRows.addView(streamingView);}else streamingView=null;
        chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void send(){
        if(busy||listening){note("Terminer la dictée ou la réponse en cours.");return;}if(!capture())return;
        final String question=draft.trim();if(question.isEmpty()&&pendingFiles.length()==0){note("Écrire, parler ou joindre un fichier.");return;}
        final String base=prefs.getString("model_base","https://ollama.com/v1"),model=prefs.getString("model_name","gemma4:31b");
        final String key;final JSONArray files,snapshot;final int tokens=prefs.getInt("tokens",8192);final double temperature=prefs.getFloat("temperature",.7f);final String prompt=prefs.getString("prompt",DEFAULT_PROMPT),savedMemory=memory;final boolean useMemory=prefs.getBoolean("use_memory",true);final int context=prefs.getInt("context",64000);
        try{if(!loadError.isEmpty())throw new IOException(loadError);key=secrets.get("model:"+ApiClient.base(base));if(key.isEmpty()){showTab(2);note("Ajouter la clé de cette adresse puis enregistrer.");return;}if(history.length()>=999)throw new IOException("Archive pleine. Exporter et ouvrir un nouveau chat.");files=new JSONArray(pendingFiles.toString());snapshot=new JSONArray(history.toString());}catch(Exception e){fail(e);return;}
        busy=true;streamingText="";final int generation=++requestGeneration;renderMessages();note("Préparation du message…");
        worker.execute(()->{boolean submitted=false;try{
            String image=null;for(int i=0;i<files.length();i++)if(files.getJSONObject(i).optBoolean("image"))image=attachments.imageData(files.getJSONObject(i));
            String body=(question.isEmpty()?"Analyse les pièces jointes.":question)+attachments.dataForChat(files);JSONObject user=LocalStore.message("user",body).put("attachments",files);snapshot.put(user);
            JSONArray messages=LocalStore.payload(snapshot,prompt,savedMemory,useMemory,image,context,tokens);
            java.util.concurrent.CountDownLatch committed=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean(false);
            runOnUiThread(()->{try{if(generation!=requestGeneration||isDestroyed())return;store.write("conversations.json",snapshot.toString());history=snapshot;draft="";pendingFiles=new JSONArray();pendingImage=null;if(input!=null)input.setText("");capture();updateAttachments();renderMessages();note("Connexion au modèle…");accepted.set(true);}catch(Exception e){busy=false;fail(e);}finally{committed.countDown();}});
            if(!committed.await(15,java.util.concurrent.TimeUnit.SECONDS)||!accepted.get()||generation!=requestGeneration)return;submitted=true;
            final long[] shown={0};ChatStream.Result result=api.stream(base,model,key,messages,temperature,tokens,(answer,reasoning)->{
                long now=SystemClock.elapsedRealtime();if(now-shown[0]<80&&answer.equals(streamingText))return;if(now-shown[0]<80&&!reasoning)return;shown[0]=now;
                runOnUiThread(()->{if(generation!=requestGeneration||isDestroyed())return;streamingText=answer;if(streamingView!=null)streamingView.setText(answer.isEmpty()?"Réflexion du modèle…":answer);note(reasoning&&answer.isEmpty()?"Réflexion du modèle…":"Réception de la réponse…");if(chatScroll!=null&&tab==0)chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));});
            });
            runOnUiThread(()->{if(generation!=requestGeneration||isDestroyed())return;busy=false;saveAnswer(result.text,result.finish);note(result.complete()?"Réponse reçue · "+model:"Réponse partielle conservée. Limite atteinte ou génération interrompue; augmenter les tokens dans Réglages.");if(tab==0&&active&&prefs.getBoolean("read_voice",true))speak(result.text);});
        }catch(Exception e){final boolean accepted=submitted;runOnUiThread(()->{if(generation!=requestGeneration||isDestroyed())return;busy=false;if(accepted&&!streamingText.trim().isEmpty())saveAnswer(streamingText,"interrupted");renderMessages();fail(e);});}});
    }
    private void saveAnswer(String answer,String finish){try{JSONArray next=new JSONArray(history.toString());next.put(LocalStore.message("assistant",answer).put("finish_reason",finish));store.write("conversations.json",next.toString());history=next;streamingText="";renderMessages();}catch(Exception e){fail(e);}}
    private String voiceBase(){String configured=prefs.getString("voice_base","");return configured.isEmpty()?prefs.getString("model_base","https://ollama.com/v1"):configured;}
    private boolean alibabaVoice(){try{AlibabaSpeech.compatible(voiceBase());return true;}catch(Exception e){return false;}}
    private String voiceKey()throws Exception{String configured=prefs.getString("voice_base","");return configured.isEmpty()?secrets.get("model:"+ApiClient.base(prefs.getString("model_base","https://ollama.com/v1"))):secrets.get("voice:"+ApiClient.base(configured));}
    private void speak(String original){
        String value=SpeechText.clean(original);stopPlayback();if(value.isEmpty()){note("Le contenu est affiché; aucun texte à lire.");return;}
        if(prefs.getBoolean("alibaba_tts",true)&&alibabaVoice()){
            voiceQueue=SpeechText.chunks(value,550);voiceIndex=0;final int generation=++voiceGeneration;nextSpeech(generation);return;
        }
        if(ttsReady){List<String> chunks=SpeechText.chunks(value,TextToSpeech.getMaxSpeechInputLength()-20);for(int i=0;i<chunks.size();i++)tts.speak(chunks.get(i),i==0?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,null,i==chunks.size()-1?"last":"part");}
        else note("Installer une voix française Android ou configurer la voix Alibaba dans Réglages.");
    }
    private void nextSpeech(int generation){
        if(generation!=voiceGeneration||!active||isDestroyed())return;if(voiceIndex>=voiceQueue.size()){note("");if(tab==0&&!busy&&prefs.getBoolean("conversation_voice",false))startListening();return;}
        final String part=voiceQueue.get(voiceIndex++),base=voiceBase();note("Préparation de la voix Alibaba…");worker.execute(()->{File audio=null;try{audio=speech.synthesize(base,voiceKey(),prefs.getString("tts_voice","Ethan"),part,getCacheDir());final File ready=audio;
            runOnUiThread(()->{if(generation!=voiceGeneration||!active||isDestroyed()){ready.delete();return;}try{player=new android.media.MediaPlayer();playingFile=ready;player.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());player.setDataSource(ready.getPath());player.setOnPreparedListener(p->{if(generation==voiceGeneration&&active){p.start();note("Lecture vocale…");}});player.setOnCompletionListener(p->{releasePlayer();nextSpeech(generation);});player.setOnErrorListener((p,what,extra)->{releasePlayer();note("Lecture audio impossible. La réponse reste affichée.");return true;});player.prepareAsync();}catch(Exception e){releasePlayer();fail(new IOException("Lecture vocale impossible."));}});
        }catch(Exception e){if(audio!=null)audio.delete();runOnUiThread(()->{if(generation==voiceGeneration){prefs.edit().putBoolean("conversation_voice",false).apply();if(conversationBox!=null)conversationBox.setChecked(false);fail(new IOException("Voix Alibaba indisponible. "+(e instanceof IOException?e.getMessage():"Vérifier le modèle vocal et les droits du compte.")));}});}});
    }
    private void releasePlayer(){if(player!=null){player.release();player=null;}if(playingFile!=null){playingFile.delete();playingFile=null;}}
    private void stopPlayback(){++voiceGeneration;speech.cancel();if(tts!=null)tts.stop();releasePlayer();voiceQueue=Collections.emptyList();}
    private void stop(){++requestGeneration;busy=false;voiceTranscribing=false;api.cancel();microphone.cancel();listening=false;stopPlayback();prefs.edit().putBoolean("conversation_voice",false).apply();if(conversationBox!=null)conversationBox.setChecked(false);if(recognizer!=null)recognizer.cancel();if(!streamingText.trim().isEmpty())saveAnswer(streamingText,"cancelled");else renderMessages();note("Microphone, lecture et requête arrêtés.");}
    private void newChat(){if(busy){note("Arrêter la réponse avant d’ouvrir un nouveau chat.");return;}new AlertDialog.Builder(this).setTitle("Nouvelle conversation").setMessage("Exporter le chat pour garder une copie. La mémoire personnelle restera intacte.").setNegativeButton("Annuler",null).setNeutralButton("Exporter",(d,w)->export("chat")).setPositiveButton("Vider le chat",(d,w)->{try{store.write("conversations.json","[]");history=new JSONArray();showTab(0);}catch(Exception e){fail(e);}}).show();}
    private void startListening(){
        if(!active||busy||listening||tab!=0)return;if(drawer!=null)drawer.setVisibility(View.GONE);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},11);return;}
        stopPlayback();if(alibabaVoice()&&prefs.getBoolean("alibaba_asr",true)){
            final String base=voiceBase(),key;try{key=voiceKey();if(key.isEmpty())throw new IOException("Ajouter la clé Alibaba dans Réglages avant la dictée.");}catch(Exception e){fail(e);return;}
            usingRecorder=true;listening=true;final int generation=++voiceGeneration;note("Je t’écoute. Pause de 2 secondes ou Terminer pour transcrire.");
            microphone.start(new VoiceCapture.Listener(){
                public void level(int level,int seconds){runOnUiThread(()->{if(generation==voiceGeneration&&active)note("Microphone · "+seconds+" s · niveau "+level+" % · Terminer");});}
                public void done(byte[] wav){runOnUiThread(()->{if(generation==voiceGeneration){listening=false;busy=true;voiceTranscribing=true;note("Transcription Alibaba…");}});worker.execute(()->{try{if(generation!=voiceGeneration){Arrays.fill(wav,(byte)0);return;}String value=speech.transcribe(base,key,wav);Arrays.fill(wav,(byte)0);runOnUiThread(()->{if(generation!=voiceGeneration||!active||isDestroyed())return;busy=false;voiceTranscribing=false;dictated(value);});}catch(Exception e){Arrays.fill(wav,(byte)0);runOnUiThread(()->{if(generation==voiceGeneration){listening=false;busy=false;voiceTranscribing=false;fail(new IOException("Transcription Alibaba : "+(e instanceof IOException?e.getMessage():"réponse invalide.")));}});}});}
                public void error(String message){runOnUiThread(()->{if(generation==voiceGeneration){listening=false;note(message);}});}
            });return;
        }
        usingRecorder=false;if(!SpeechRecognizer.isRecognitionAvailable(this)){note("Pas de service de dictée Android. Configurer la voix Alibaba dans Réglages.");return;}
        if(recognizer==null){recognizer=SpeechRecognizer.createSpeechRecognizer(this);recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){note("Je t’écoute…");}public void onBeginningOfSpeech(){}public void onRmsChanged(float v){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){note("Transcription Android…");}
            public void onError(int code){listening=false;note("Dictée Android interrompue ("+code+"). Réessayer ou utiliser Alibaba.");}
            public void onResults(Bundle b){listening=false;ArrayList<String> values=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values!=null&&!values.isEmpty()&&active&&tab==0)dictated(values.get(0));}
            public void onPartialResults(Bundle b){ArrayList<String> values=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values!=null&&!values.isEmpty())note("Dictée : "+values.get(0));}public void onEvent(int type,Bundle b){}
        });}
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fr-CA").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);listening=true;recognizer.startListening(intent);
    }
    private void dictated(String value){draft=(draft.trim().isEmpty()?"":draft+" ")+value;if(input!=null)input.setText(draft);capture();note("Texte reconnu. Corriger ou Envoyer.");if(prefs.getBoolean("conversation_voice",false))send();}
    private void memoryView(){LinearLayout box=form();box.addView(text("Ma mémoire persistante",23,WHITE));box.addView(text("Colle ici ton bagage personnel. Le texte reste dans les données privées de 3AI. Il est ajouté aux messages du modèle quand l’option est activée.",16,MUTED));
        CheckBox include=new CheckBox(this);include.setText("Utiliser cette mémoire dans le chat");include.setTextColor(WHITE);include.setChecked(prefs.getBoolean("use_memory",true));include.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("use_memory",v).apply());box.addView(include);
        memoryInput=edit("Coller mon bloc mémoire…",memory,true);memoryInput.setSaveEnabled(false);memoryInput.setMinLines(12);box.addView(memoryInput,new LinearLayout.LayoutParams(-1,-2));
        row(box,button("Enregistrer",()->{if(capture())note("Mémoire enregistrée sur ce téléphone.");}),button("Importer",()->pick("text/*",102)),button("Exporter",()->export("memory")));
        row(box,button("Envoyer à Supabase",this::syncCloud),button("Relire Supabase",this::pullCloud));
        box.addView(text("La synchronisation distante devient disponible après configuration du projet Supabase séparé et connexion à ton compte. Aucun texte n’est téléversé automatiquement.",15,MUTED));
    }
    private void voiceSettings(LinearLayout box){
        box.addView(text("Voix · Alibaba",23,WHITE));box.addView(text("DeepSeek garde la conversation. Qwen3-ASR-Flash transcrit le micro; Qwen3-TTS-Flash lit les réponses en français. Ces appels utilisent ton compte Alibaba. Sans adresse vocale séparée, la clé du modèle est réutilisée uniquement sur le même serveur Alibaba.",15,MUTED));
        CheckBox asr=new CheckBox(this);asr.setText("Utiliser Alibaba pour la dictée");asr.setTextColor(WHITE);asr.setChecked(prefs.getBoolean("alibaba_asr",true));asr.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("alibaba_asr",on).apply());box.addView(asr);
        CheckBox natural=new CheckBox(this);natural.setText("Voix Alibaba naturelle");natural.setTextColor(WHITE);natural.setChecked(prefs.getBoolean("alibaba_tts",true));natural.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("alibaba_tts",on).apply());box.addView(natural);
        box.addView(button("Choisir la voix",()->new AlertDialog.Builder(this).setTitle("Voix française Alibaba").setItems(new String[]{"Ethan · masculine","Cherry · féminine","Serena · féminine douce"},(d,i)->{prefs.edit().putString("tts_voice",new String[]{"Ethan","Cherry","Serena"}[i]).apply();note("Voix enregistrée.");}).show()));
        box.addView(button("Configurer une adresse vocale séparée",this::voiceConnection));
        box.addView(button("Tester la voix",()->speak("Bonjour Erick. La voix est prête. Tu peux me parler avec le bouton Parler.")));
        box.addView(text("La dictée s’arrête après deux secondes de silence, ou en touchant Terminer. Un enregistrement dure au plus une minute. Il est envoyé pour transcription, puis effacé de la mémoire de l’application.",15,MUTED));
    }
    private void voiceConnection(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),0,dp(16),0);
        EditText url=field(box,"Adresse Alibaba HTTPS",prefs.getString("voice_base",""),false),key=field(box,"Clé vocale — vide pour garder la clé enregistrée","",false);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSaveEnabled(false);
        new AlertDialog.Builder(this).setTitle("Connexion vocale Alibaba").setView(box).setNegativeButton("Annuler",null).setNeutralButton("Utiliser la connexion du modèle",(d,w)->{prefs.edit().remove("voice_base").apply();note("La voix utilisera la connexion Alibaba du modèle.");}).setPositiveButton("Enregistrer",(d,w)->{try{String base=ApiClient.base(url.getText().toString());AlibabaSpeech.compatible(base);String value=key.getText().toString().trim();if(!value.isEmpty())secrets.set("voice:"+base,value);prefs.edit().putString("voice_base",base).commit();key.setText("");note("Connexion vocale enregistrée. Le modèle du chat reste inchangé.");}catch(Exception e){fail(e);}}).show();
    }
    private void serviceAccess(LinearLayout box){
        box.addView(text("Services et fichiers",21,ORANGE));box.addView(text("Les clés sont protégées sur ce téléphone. Se connecter à un site dans Web ne donne pas automatiquement sa clé API à Jarvis.",15,MUTED));
        row(box,button("GitHub · accès API",this::githubAccess),button("Lire un fichier GitHub",this::githubFile));
        row(box,button("Alibaba · console",()->openBrowser("https://modelstudio.console.alibabacloud.com/")),button("Modèle et voix",()->showTab(2)));
        row(box,button("Ajouter une API HTTPS",this::addService),button("Mes API",this::readService));
        box.addView(text("GitHub : lecture du compte et des fichiers autorisés par ton jeton. Autres API : lecture HTTPS explicite avec une clé Bearer. Le résultat peut être ajouté au brouillon avant envoi au modèle. Chaque service garde ses permissions propres.",15,MUTED));
    }
    private void githubAccess(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),0,dp(16),0);box.addView(text("Créer un jeton GitHub avec accès en lecture aux dépôts souhaités, puis le coller ici.",16,MUTED));
        EditText token=field(box,"Jeton GitHub — vide pour garder celui enregistré","",false);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setSaveEnabled(false);
        box.addView(button("Créer / gérer mes jetons GitHub",()->openBrowser("https://github.com/settings/personal-access-tokens")));
        new AlertDialog.Builder(this).setTitle("Accès GitHub").setView(box).setNegativeButton("Fermer",null).setNeutralButton("Retirer l’accès",(d,w)->{try{secrets.set("service:github","");note("Jeton GitHub retiré de 3AI.");}catch(Exception e){fail(e);}}).setPositiveButton("Enregistrer et vérifier",(d,w)->{final String value=token.getText().toString().trim();token.setText("");note("Vérification GitHub…");worker.execute(()->{try{if(!value.isEmpty())secrets.set("service:github",value);String key=secrets.get("service:github");if(key.isEmpty())throw new IOException("Ajouter un jeton GitHub.");JSONObject result=new JSONObject(new ApiClient().request("https://api.github.com","/user","GET",null,Collections.singletonMap("Authorization","Bearer "+key)));runOnUiThread(()->note("GitHub connecté : "+result.optString("login")+". Les permissions restent celles de ton jeton."));}catch(Exception e){runOnUiThread(()->fail(e));}});}).show();
    }
    private String segment(String value)throws Exception{return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20");}
    private void githubFile(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),0,dp(16),0);EditText repo=field(box,"Dépôt : propriétaire/nom",prefs.getString("github_repo",""),false),path=field(box,"Chemin du fichier",prefs.getString("github_path","README.md"),false),branch=field(box,"Branche ou commit",prefs.getString("github_ref","main"),false);
        new AlertDialog.Builder(this).setTitle("Lire un fichier GitHub").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Lire",(d,w)->{final String repository=repo.getText().toString().trim(),filePath=path.getText().toString().trim(),ref=branch.getText().toString().trim();prefs.edit().putString("github_repo",repository).putString("github_path",filePath).putString("github_ref",ref).apply();note("Lecture GitHub…");worker.execute(()->{try{
            String[] pieces=repository.split("/",-1);if(pieces.length!=2||pieces[0].isEmpty()||pieces[1].isEmpty()||filePath.isEmpty())throw new IOException("Entrer propriétaire/dépôt et un chemin de fichier.");StringBuilder route=new StringBuilder("/repos/").append(segment(pieces[0])).append('/').append(segment(pieces[1])).append("/contents/");for(String component:filePath.split("/")){if(component.isEmpty()||component.equals(".."))throw new IOException("Chemin de fichier invalide.");route.append(segment(component)).append('/');}route.setLength(route.length()-1);route.append("?ref=").append(segment(ref));String key=secrets.get("service:github");
            Map<String,String> headers=new HashMap<>();headers.put("Accept","application/vnd.github+json");headers.put("X-GitHub-Api-Version","2022-11-28");headers.put("User-Agent","3AI");if(!key.isEmpty())headers.put("Authorization","Bearer "+key);
            JSONObject result=new JSONObject(new ApiClient().request("https://api.github.com",route.toString(),"GET",null,headers));if(!"file".equals(result.optString("type"))||result.optInt("size")>131072||!"base64".equals(result.optString("encoding")))throw new IOException("Choisir un fichier texte de 128 Ko maximum.");byte[] bytes=android.util.Base64.decode(result.getString("content"),android.util.Base64.DEFAULT);String text=LocalStore.readImportText(new ByteArrayInputStream(bytes),131072);runOnUiThread(()->confirmImport("GitHub : "+repository+" / "+filePath+" @ "+ref+"\n"+text));
        }catch(Exception e){runOnUiThread(()->fail(e));}});}).show();
    }
    private void addService(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),0,dp(16),0);EditText name=field(box,"Nom du service","",false),url=field(box,"Adresse de lecture HTTPS exacte","",false),key=field(box,"Clé API Bearer","",false);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSaveEnabled(false);
        new AlertDialog.Builder(this).setTitle("Ajouter une API HTTPS").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{try{String endpoint=ApiClient.base(url.getText().toString()),id=UUID.randomUUID().toString(),label=name.getText().toString().trim();if(label.isEmpty())throw new IOException("Nom du service manquant.");JSONArray list=new JSONArray(prefs.getString("services","[]"));if(list.length()>=12)throw new IOException("Douze connexions maximum.");secrets.set("service:"+id,key.getText().toString().trim());key.setText("");list.put(new JSONObject().put("id",id).put("name",label).put("url",endpoint));prefs.edit().putString("services",list.toString()).commit();note("Connexion enregistrée. Mes API permet une lecture explicite.");}catch(Exception e){fail(e);}}).show();
    }
    private void readService(){try{JSONArray list=new JSONArray(prefs.getString("services","[]"));if(list.length()==0){note("Ajouter une API HTTPS pour commencer.");return;}String[] names=new String[list.length()];for(int i=0;i<names.length;i++)names[i]=list.getJSONObject(i).getString("name");new AlertDialog.Builder(this).setTitle("Mes API · sélectionner").setItems(names,(d,i)->{JSONObject item=list.optJSONObject(i);new AlertDialog.Builder(this).setTitle(item.optString("name")).setMessage(item.optString("url")).setNegativeButton("Fermer",null).setNeutralButton("Retirer",(dialog,w)->{try{secrets.set("service:"+item.getString("id"),"");list.remove(i);prefs.edit().putString("services",list.toString()).commit();note("Connexion retirée.");}catch(Exception e){fail(e);}}).setPositiveButton("Lire HTTPS",(dialog,w)->{note("Lecture du service…");worker.execute(()->{try{String key=secrets.get("service:"+item.getString("id"));String value=new ApiClient().request(item.getString("url"),"","GET",null,key.isEmpty()?Collections.emptyMap():Collections.singletonMap("Authorization","Bearer "+key));LocalStore.validateImportText(value);if(value.length()>131072)throw new IOException("Réponse trop longue pour le brouillon (128 Ko maximum).");runOnUiThread(()->confirmImport("Service : "+item.optString("name")+"\n"+value));}catch(Exception e){runOnUiThread(()->fail(e));}});}).show();}).show();}catch(Exception e){fail(e);}}
    private void settingsView(){LinearLayout box=form();box.addView(text("Assistant et modèle",23,WHITE));
        EditText name=field(box,"Nom personnel de l’assistant",settingsValue("assistant_name","Jarvis"),false);
        box.addView(text("Adresse, modèle et clé : API Chat Completions compatible. La saisie est conservée avant validation.",15,MUTED));
        final EditText base=field(box,"Adresse API HTTPS",settingsValue("model_base","https://ollama.com/v1"),false),model=field(box,"Modèle",settingsValue("model_name","gemma4:31b"),false),key=field(box,"Clé d’accès — laisser vide pour garder celle de cette adresse",pendingSettingsKey(),false);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSaveEnabled(false);key.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        row(box,button("Navigateur intégré",()->openBrowser(null)),button("Mes clés Ollama",()->openBrowser("https://ollama.com/settings/keys")));

        EditText prompt=field(box,"Consignes / prompt de Jarvis",settingsValue("prompt",DEFAULT_PROMPT),true);prompt.setMinLines(6);
        EditText temp=field(box,"Température (0 à 2)",settingsValue("temperature",Float.toString(prefs.getFloat("temperature",.7f))),false);temp.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText tokens=field(box,"Maximum de tokens par réponse (128 à 65536)",settingsValue("tokens",Integer.toString(prefs.getInt("tokens",8192))),false);tokens.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText context=field(box,"Budget de contexte estimé (2048 à 262144)",settingsValue("context",Integer.toString(prefs.getInt("context",64000))),false);context.setInputType(InputType.TYPE_CLASS_NUMBER);
        settingsFields.put("assistant_name",name); settingsFields.put("model_base",base); settingsFields.put("model_name",model);
        settingsFields.put("prompt",prompt); settingsFields.put("temperature",temp); settingsFields.put("tokens",tokens); settingsFields.put("context",context); settingsKey=key;
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){if(!changingSettings){settingsDirty=true;persistSettingsDraft();}} public void afterTextChanged(Editable e){}};
        for(EditText f:settingsFields.values()){f.setSaveEnabled(false);f.addTextChangedListener(watcher);} key.addTextChangedListener(watcher);
        box.addView(text("La mémoire est envoyée intégralement, avec les 40 derniers messages au maximum. Le budget est estimé; un refus du serveur sera affiché. Le gros modèle reste sur le cloud.",15,MUTED));
        box.addView(button("Enregistrer les réglages",()->{try{String endpoint=ApiClient.base(base.getText().toString());String modelName=model.getText().toString().trim();String newKey=key.getText().toString().trim();
            if(!newKey.isEmpty()){
                if(newKey.startsWith("Bearer ")||newKey.contains("\n")||newKey.contains("\r"))throw new IOException("Coller uniquement la clé, sans Bearer ni saut de ligne.");
                secrets.set("model:"+endpoint,newKey);
            }
            if(modelName.isEmpty()){note("Adresse et clé conservées. Ajouter le nom du modèle puis enregistrer. La configuration du chat n’a pas encore changé.");return;}
            float t=Float.parseFloat(temp.getText().toString());int n=Integer.parseInt(tokens.getText().toString()),c=Integer.parseInt(context.getText().toString());
            if(modelName.isEmpty()||Float.isNaN(t)||t<0||t>2||n<128||n>65536||c<2048||c>262144||c<=n)throw new IOException("Vérifier le modèle et les limites de génération.");
            if(!prefs.edit().putString("model_base",endpoint).putString("model_name",modelName).putString("assistant_name",name.getText().toString().trim()).putString("prompt",prompt.getText().toString()).putFloat("temperature",t).putInt("tokens",n).putInt("context",c).commit())throw new IOException("Réglages non enregistrés : stockage indisponible.");
            clearSettingsDraft(); changingSettings=true; key.setText(""); changingSettings=false; settingsDirty=false; note("Réglages enregistrés. La clé est protégée sur ce téléphone.");
        }catch(Exception e){fail(e);}}));
        box.addView(button("Tester la connexion",()->{if(!base.getText().toString().trim().replaceAll("/+$","").equals(prefs.getString("model_base","https://ollama.com/v1"))||!model.getText().toString().trim().equals(prefs.getString("model_name","gemma4:31b"))||!key.getText().toString().trim().isEmpty()){note("Modifications non enregistrées. Appuyer sur Enregistrer les réglages, puis Tester la connexion.");return;}testModel();}));
        box.addView(button("Diagnostic connexion",this::connectionDiagnostic));
        voiceSettings(box);
        box.addView(text("Supabase · stockage séparé",23,WHITE));EditText cloudUrl=field(box,"URL du projet Supabase 3AI",prefs.getString("supabase_url",""),false),publicKey=field(box,"Clé publique Supabase (publishable)",prefs.getString("supabase_key",""),false);
        box.addView(button("Enregistrer Supabase",()->{try{String url=ApiClient.base(cloudUrl.getText().toString());String k=publicKey.getText().toString().trim();if(k.isEmpty()||k.startsWith("sb_secret_"))throw new IOException("Utiliser uniquement la clé publique Supabase.");prefs.edit().putString("supabase_url",url).putString("supabase_key",k).commit();note("Projet enregistré. Se connecter pour synchroniser.");}catch(Exception e){fail(e);}}));
        row(box,button("Se connecter",this::cloudLogin),button("Se déconnecter",()->worker.execute(()->{try{cloud.logout();runOnUiThread(()->note("Déconnecté de Supabase."));}catch(Exception e){runOnUiThread(()->fail(e));}})));
        box.addView(text("3AI "+BuildConfig.VERSION_NAME+" · application indépendante\nVoix : Alibaba si configuré; service Android sinon. Le microphone reste actif seulement dans le chat au premier plan.\nAucune clé du compte GitHub n’est incluse dans cet APK.",15,MUTED));
    }
    private String settingsValue(String name,String fallback){return prefs.getString("settings_draft_"+name,prefs.contains(name)?String.valueOf(prefs.getAll().get(name)):fallback);}
    private String pendingSettingsKey(){if(!prefs.contains("settings_draft_model_base"))return "";try{return secrets.get("settings_draft_key");}catch(Exception e){fail(new IOException("Saisie de clé illisible. La copie existante est conservée."));return "";}}
    private boolean persistSettingsDraft(){
        if(!settingsDirty||settingsKey==null)return true;
        try{
            secrets.set("settings_draft_key",settingsKey.getText().toString());
            SharedPreferences.Editor editor=prefs.edit();
            for(Map.Entry<String,EditText> f:settingsFields.entrySet())editor.putString("settings_draft_"+f.getKey(),f.getValue().getText().toString());
            if(!editor.commit())throw new IOException("Saisie non enregistrée : stockage indisponible.");
            return true;
        }catch(Exception e){fail(new IOException("Conservation de la saisie impossible. Garder cet écran ouvert et réessayer."));return false;}
    }
    private void clearSettingsDraft()throws Exception{
        SharedPreferences.Editor editor=prefs.edit();for(String name:settingsFields.keySet())editor.remove("settings_draft_"+name);
        if(!editor.commit())throw new IOException("Réglages validés; effacement de la saisie temporaire impossible.");
        secrets.set("settings_draft_key","");
    }
    private void openBrowser(String url){if(!capture())return;Intent intent=new Intent(this,BrowserActivity.class);if(url!=null)intent.putExtra("url",url);startActivity(intent);}
    private EditText field(LinearLayout parent,String label,String value,boolean multi){parent.addView(text(label,16,MUTED));EditText e=edit(label,value,multi);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private void testModel(){if(busy){note("Attendre la requête actuelle.");return;}busy=true;note("Test de la configuration enregistrée…");worker.execute(()->{try{String base=prefs.getString("model_base","https://ollama.com/v1"),key=secrets.get("model:"+ApiClient.base(base));JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","Réponds uniquement TREEAI_OK."));String result=api.chat(base,prefs.getString("model_name","gemma4:31b"),key,messages,.7,prefs.getInt("tokens",8192));runOnUiThread(()->{busy=false;note("Réponse du test : "+result);});}catch(Exception e){runOnUiThread(()->{busy=false;fail(e);});}});}
    private void cloudLogin(){LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);EditText email=edit("Adresse courriel","",false),password=edit("Mot de passe Supabase","",false);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(email);box.addView(password);new AlertDialog.Builder(this).setTitle("Compte Supabase 3AI").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Connexion",(d,w)->{String e=email.getText().toString(),p=password.getText().toString();password.setText("");note("Connexion Supabase…");worker.execute(()->{try{cloud.login(e,p);runOnUiThread(()->note("Connecté. La mémoire peut être synchronisée."));}catch(Exception err){runOnUiThread(()->fail(err));}});}).show();}
    private void syncCloud(){capture();final String text=memory;final JSONArray snapshot;try{snapshot=new JSONArray(history.toString());}catch(Exception e){fail(e);return;}note("Synchronisation vers Supabase…");worker.execute(()->{try{long revision=cloud.saveMemory(text);cloud.saveMessages(snapshot);runOnUiThread(()->note("Mémoire confirmée sur Supabase · révision "+revision+". Conversations envoyées; copies locales conservées."));}catch(Exception e){runOnUiThread(()->fail(e));}});}
    private void pullCloud(){note("Lecture Supabase…");worker.execute(()->{try{JSONObject data=cloud.readMemory();runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Mémoire distante · révision "+data.optLong("revision")).setMessage("Remplacer le texte local par la mémoire distante? Exporter d’abord le texte local si nécessaire.").setNegativeButton("Annuler",null).setPositiveButton("Remplacer",(d,w)->{try{String value=data.getString("body");store.write("memory.txt",value);memory=value;if(memoryInput!=null)memoryInput.setText(value);worker.execute(()->{try{cloud.acceptRevision(data.getLong("revision"));runOnUiThread(()->note("Mémoire distante chargée sur ce téléphone."));}catch(Exception e){runOnUiThread(()->fail(e));}});}catch(Exception e){fail(e);}}).show());}catch(Exception e){runOnUiThread(()->fail(e));}});}
    private void accessView(){LinearLayout box=form();box.addView(text("Accès de mon assistant",23,WHITE));serviceAccess(box);connectionStatus=text("",17,MUTED);box.addView(connectionStatus);updateAccess();
        row(box,button("Autoriser Shizuku",this::authorizeShizuku),button("Diagnostic shell",this::connectShell));
        box.addView(text("Le chat et le modèle utilisent Internet (Wi-Fi ou données mobiles), sans Shizuku. Shizuku sert aux accès locaux au téléphone. Pour le démarrer sans root par débogage sans fil, Android demande une connexion Wi-Fi; certains systèmes arrêtent le service en quittant ce réseau. La disponibilité affichée ici est vérifiée en direct.",16,MUTED));
        box.addView(button("Aide Shizuku / hors Wi-Fi",this::shizukuHelp));
        box.addView(text("La connexion Shizuku autorise le diagnostic shell de cette version. Le modèle ne lance pas de commandes Android et ne lit pas les données privées des autres applis.",16,MUTED));
        row(box,button("Ouvrir AIV",this::openAiv),button("Importer rapport AIV",()->pick("*/*",103)));
        box.addView(text("AIV peut partager un texte ou un rapport JSON vers 3AI. Tu peux le relire dans le chat avant de l’envoyer au modèle.",16,MUTED));
        row(box,button("Contacts",()->personalData(false)),button("Agenda",()->personalData(true)));
        box.addView(button("Demander les accès manquants",this::requestMissingAccess));
        box.addView(text("Après une mise à jour, les droits refusés peuvent rester refusés. Si Android bloque les demandes, ouvre AIV mis à jour → Contrôle des permissions → Réparer les accès de 3AI, puis reviens ici. Shizuku demande une autorisation séparée.",16,MUTED));
        box.addView(button("Réglages Android de 3AI",()->open(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));
        box.addView(text("Les fichiers et les images passent par le sélecteur Android. Contacts et agenda demandent une autorisation à leur première utilisation. Aucun accès n’est présenté comme accordé avant confirmation d’Android.",16,MUTED));
    }
    private String permissionStatus(String permission){return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED?"autorisé":"à autoriser";}
    private void updateAccess(){if(connectionStatus==null)return;String shizuku="indisponible";try{boolean alive=Shizuku.pingBinder();boolean allowed=alive&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;shizuku=!alive?"non démarré":!allowed?"autorisation requise":"connecté · UID "+Shizuku.getUid();}catch(Exception ignored){}connectionStatus.setText("Shizuku : "+shizuku+"\nMicrophone : "+permissionStatus(Manifest.permission.RECORD_AUDIO)+"\nContacts : "+permissionStatus(Manifest.permission.READ_CONTACTS)+"\nAgenda : "+permissionStatus(Manifest.permission.READ_CALENDAR));}
    private void requestMissingAccess(){java.util.ArrayList<String> missing=new java.util.ArrayList<>();for(String permission:new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_CONTACTS,Manifest.permission.READ_CALENDAR})if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)missing.add(permission);if(missing.isEmpty()){updateAccess();note("Ces trois accès sont déjà autorisés par Android.");return;}requestPermissions(missing.toArray(new String[0]),14);}
    private void shizukuHelp(){new AlertDialog.Builder(this).setTitle("Shizuku quand je quitte le Wi-Fi").setMessage("Dans les options développeur Android, garder Débogage USB activé, et activer Désactiver le délai d’expiration des autorisations ADB si cette option est disponible. Autoriser aussi Shizuku à fonctionner en arrière-plan. Démarrer Shizuku sur Wi-Fi, puis tester le passage aux données mobiles et regarder son état ici.\n\nCes réglages suivent le guide Shizuku et ne garantissent pas le résultat sur chaque Samsung. Le chat reste indépendant. Si Android arrête le service, une application ordinaire ne peut pas le redémarrer silencieusement avec les droits ADB. Aucun réglage Android n’est modifié automatiquement.").setNegativeButton("Fermer",null).setPositiveButton("Guide officiel",(d,w)->open(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")))).show();}
    private void authorizeShizuku(){try{if(!Shizuku.pingBinder()){note("Démarrer Shizuku, puis revenir ici.");open(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")));return;}if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){updateAccess();note("Shizuku autorise déjà 3AI.");}else Shizuku.requestPermission(50);}catch(Exception e){fail(e);}}
    private void connectShell(){try{if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){authorizeShizuku();return;}if(shell!=null){runShellDiagnostic();return;}note("Connexion au service shell…");Shizuku.bindUserService(shellArgs,shellConnection);shellBound=true;}catch(Exception e){fail(e);}}
    private void runShellDiagnostic(){final IAssistantShell service=shell;if(service==null)return;worker.execute(()->{try{String diagnostic=service.diagnostic();runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Diagnostic Shizuku").setMessage(diagnostic).setPositiveButton("Fermer",null).setNeutralButton("Ajouter au chat",(d,w)->addDraft("Diagnostic Shizuku :\n"+diagnostic)).show());}catch(Exception e){runOnUiThread(()->fail(e));}});}
    private void openAiv(){Intent intent=getPackageManager().getLaunchIntentForPackage("com.allinvisible.aiv");if(intent==null)intent=getPackageManager().getLaunchIntentForPackage("fr.erick.journallocal");if(intent==null){note("All In Visible n’est pas installé dans ce profil Android.");return;}open(intent);}
    private void open(Intent intent){try{startActivity(intent);}catch(ActivityNotFoundException e){note("Aucune application disponible pour cette action.");}catch(Exception e){fail(e);}}
    private void personalData(boolean calendar){String permission=calendar?Manifest.permission.READ_CALENDAR:Manifest.permission.READ_CONTACTS;if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{permission},calendar?13:12);return;}note("Lecture locale…");worker.execute(()->{try{StringBuilder out=new StringBuilder(calendar?"Agenda des 7 prochains jours (50 entrées maximum) :\n":"Contacts (50 premières entrées maximum) :\n");Cursor cursor;
            if(calendar){Uri.Builder builder=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(builder,System.currentTimeMillis());ContentUris.appendId(builder,System.currentTimeMillis()+7*86400000L);cursor=getContentResolver().query(builder.build(),new String[]{CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN},null,null,CalendarContract.Instances.BEGIN+" ASC");}
            else cursor=getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER},null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC");
            if(cursor!=null)try(Cursor c=cursor){int count=0;while(c.moveToNext()&&count++<50)out.append(c.getString(0)).append(" · ").append(calendar?new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.CANADA_FRENCH).format(new Date(c.getLong(1))):c.getString(1)).append('\n');}
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle(calendar?"Agenda local":"Contacts locaux").setMessage(out.toString()).setNegativeButton("Fermer",null).setPositiveButton("Ajouter au chat",(d,w)->addDraft(out.toString())).show());
        }catch(Exception e){runOnUiThread(()->fail(e));}});}
    private void addDraft(String text){capture();draft=(draft.isEmpty()?"":draft+"\n\n")+text;if(input!=null)input.setText(draft);showTab(0);input.setText(draft);note("Texte joint au brouillon. Il sera envoyé seulement en appuyant sur Envoyer.");}
    private void pick(String mime,int request){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,request);}
    private void export(String kind){capture();exportKind=kind;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(kind.equals("memory")?"text/plain":"application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,kind.equals("memory")?"3AI-memoire.txt":"3AI-conversations.json");startActivityForResult(intent,104);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();worker.execute(()->{try{
        if(request==104){String value=exportKind.equals("memory")?memory:history.toString();try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Export impossible.");out.write(value.getBytes("UTF-8"));}runOnUiThread(()->note("Export enregistré."));return;}
        if(request==101||request==105){JSONObject item=attachments.importFile(uri,request==101);runOnUiThread(()->{try{if(pendingFiles.length()>=4)throw new IOException("Quatre pièces jointes maximum par message.");if(item.optBoolean("image"))for(int i=0;i<pendingFiles.length();i++)if(pendingFiles.getJSONObject(i).optBoolean("image"))throw new IOException("Une image par message. Retirer l’image du brouillon pour la remplacer.");pendingFiles.put(item);showTab(0);capture();updateAttachments();note("Pièce jointe prête. Envoyer pour la transmettre au modèle.");}catch(Exception e){try{attachments.file(item).delete();}catch(Exception ignored){}fail(e);}});return;}
        String value;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Fichier inaccessible.");value=LocalStore.readImportText(in,request==102?2097152:1048576);}
        if(request==102)runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Importer la mémoire").setMessage("Remplacer le texte local par ce fichier? Exporter le texte actuel pour en garder une copie.").setNegativeButton("Annuler",null).setPositiveButton("Remplacer",(d,w)->{try{store.write("memory.txt",value);memory=value;if(memoryInput!=null)memoryInput.setText(value);showTab(1);note("Mémoire importée.");}catch(Exception e){fail(e);}}).show());
        else runOnUiThread(()->confirmImport(value));
    }catch(Exception e){runOnUiThread(()->fail(e));}});}
    private void handleIncoming(Intent intent){if(!Intent.ACTION_SEND.equals(intent.getAction()))return;String value=intent.getStringExtra(Intent.EXTRA_TEXT);if(value!=null){if(value.length()>1048576){note("Texte partagé trop volumineux.");return;}try{LocalStore.validateImportText(value);confirmImport(value);}catch(IOException e){fail(e);}return;}Uri uri=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class):intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null&&"content".equals(uri.getScheme()))onActivityResult(intent.getType()!=null&&intent.getType().startsWith("image/")?101:105,RESULT_OK,new Intent().setData(uri));}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);updateAccess();boolean granted=grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED;if(!granted){note("Autorisation refusée. Tu peux la modifier dans les réglages Android de 3AI.");return;}if(request==11)startListening();else if(request==12)personalData(false);else if(request==13)personalData(true);else updateAccess();}
    @Override protected void onResume(){super.onResume();active=true;updateAccess();}
    @Override protected void onPause(){capture();active=false;if(recognizer!=null){recognizer.cancel();listening=false;}microphone.cancel();stopPlayback();if(listening){listening=false;note("Dictée arrêtée en quittant le chat.");}if(voiceTranscribing){voiceTranscribing=false;busy=false;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){capture();state.putInt("tab",tab);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){++requestGeneration;api.cancel();microphone.cancel();stopPlayback();worker.shutdownNow();if(recognizer!=null)recognizer.destroy();if(tts!=null)tts.shutdown();Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);Shizuku.removeRequestPermissionResultListener(permissionListener);if(shellBound){try{Shizuku.unbindUserService(shellArgs,shellConnection,true);}catch(Exception ignored){}}super.onDestroy();}
}
