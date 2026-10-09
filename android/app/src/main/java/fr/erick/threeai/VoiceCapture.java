package fr.erick.threeai;

import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;

/** Foreground-only mono PCM, stops on silence or after 60 seconds. */
final class VoiceCapture {
    interface Listener { void level(int level,int seconds); void done(byte[] wav); void error(String message); }
    private volatile boolean finish,cancelled;
    private Thread thread;
    void finish(){finish=true;}
    void cancel(){cancelled=true;finish=true;}
    void start(Listener listener){
        if(thread!=null&&thread.isAlive())throw new IllegalStateException("Microphone déjà occupé.");finish=false;cancelled=false;
        thread=new Thread(()->{
            AudioRecord recorder=null;
            try{
                int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IOException("Ce microphone ne prend pas en charge l’enregistrement demandé.");
                recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,6400));
                if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone indisponible. Vérifier l’accès micro Android.");
                recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("Le microphone n’a pas démarré.");
                ByteArrayOutputStream pcm=new ByteArrayOutputStream();short[] samples=new short[1600];long start=SystemClock.elapsedRealtime(),lastVoice=start;boolean heard=false;int loudFrames=0;
                while(!finish&&!cancelled&&SystemClock.elapsedRealtime()-start<60000){
                    int n=recorder.read(samples,0,samples.length);if(n<0)throw new IOException("L’enregistrement a été interrompu par Android.");if(n==0)continue;
                    double square=0;for(int i=0;i<n;i++){square+=(double)samples[i]*samples[i];pcm.write(samples[i]&255);pcm.write((samples[i]>>8)&255);}int rms=(int)Math.sqrt(square/n);
                    long now=SystemClock.elapsedRealtime();if(rms>350){lastVoice=now;if(++loudFrames>=2)heard=true;}
                    listener.level(Math.min(100,rms/40),(int)((now-start)/1000));
                    if(heard&&now-lastVoice>2000)break;if(!heard&&now-start>15000)break;
                }
                if(cancelled)return;if(!heard||pcm.size()<6400)throw new IOException("Aucune parole détectée. Vérifier le microphone, puis réessayer.");listener.done(wav(pcm.toByteArray()));
            }catch(Exception e){if(!cancelled)listener.error(e instanceof IOException?e.getMessage():"Microphone indisponible. Fermer les autres applications qui l’utilisent.");}
            finally{if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}}
        },"threeai-microphone");thread.start();
    }
    static byte[] wav(byte[] pcm){
        ByteBuffer b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36+pcm.length).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);return b.array();
    }
}
