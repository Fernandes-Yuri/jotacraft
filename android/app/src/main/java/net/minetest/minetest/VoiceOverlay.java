package net.minetest.minetest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

/**
 * JoaoCraft voice overlay: floating mic button + Android TTS for the assistant.
 * No engine/JNI changes needed: voice goes through the assistente-bridge
 * (same server as the game, port 8090). The joao_assistente mod polls
 * pending voice commands and executes them as if the player typed them.
 */
@Keep
public class VoiceOverlay {
	private static final String PREFS = "joaocraft";
	private static final int REQ_MIC = 1979;

	private final Activity activity;
	private final Handler main = new Handler(Looper.getMainLooper());
	private SpeechRecognizer recognizer;
	private TextToSpeech tts;
	private boolean listening = false;
	private TextView micButton;
	private volatile boolean polling = false;
	private long lastReplyTs = 0;

	public VoiceOverlay(Activity activity) {
		this.activity = activity;
	}

	public static String getServer(Activity a) {
		return a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
				.getString("server", "http://192.168.20.69:8090");
	}

	public static String getPlayer(Activity a) {
		return a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
				.getString("player", "jottinhafjs111");
	}

	public void attach() {
		main.post(() -> {
			try {
				FrameLayout root = new FrameLayout(activity);
				FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
						dp(56), dp(56), Gravity.END | Gravity.BOTTOM);
				lp.bottomMargin = dp(150);
				lp.rightMargin = dp(12);
				micButton = new TextView(activity);
				micButton.setText("\uD83C\uDFA4"); // mic emoji, no asset needed
				micButton.setTextSize(26);
				micButton.setGravity(Gravity.CENTER);
				micButton.setBackgroundColor(0xAA222222);
				micButton.setOnClickListener(v -> onMicTap());
				micButton.setOnLongClickListener(v -> {
					showConfigDialog();
					return true;
				});
				root.addView(micButton, lp);
				activity.getWindow().addContentView(root,
						new ViewGroup.LayoutParams(
								ViewGroup.LayoutParams.MATCH_PARENT,
								ViewGroup.LayoutParams.MATCH_PARENT));
			} catch (Exception e) {
				toast("Voz: " + e.getMessage());
			}
		});
		initTts();
		startReplyPolling();
		initOfflineVoice();
	}

	// Voz 100% offline (sherpa-onnx): baixa os modelos uma vez e depois
	// funciona sem internet e sem Google. Enquanto baixa, o mic usa Google.
	private void initOfflineVoice() {
		new Thread(() -> {
			boolean ready = SherpaVoice.tryLoad(activity);
			if (ready) {
				toast("Voz offline pronta!");
				return;
			}
			SherpaVoice.ensureModels(activity, new SherpaVoice.ProgressCb() {
				@Override public void onProgress(String file, int percent) {
					if (percent % 25 == 0) toast("Baixando voz: " + percent + "%");
				}
				@Override public void onDone(boolean ok, String msg) {
					toast(ok ? "Voz offline pronta!" : msg);
				}
			});
		}).start();
	}

	public void detach() {
		polling = false;
		try {
			if (recognizer != null) {
				recognizer.destroy();
				recognizer = null;
			}
		} catch (Exception ignored) {}
		try {
			if (tts != null) {
				tts.stop();
				tts.shutdown();
				tts = null;
			}
		} catch (Exception ignored) {}
	}

	private int dp(int v) {
		return (int) (v * activity.getResources().getDisplayMetrics().density);
	}

	private void toast(String s) {
		main.post(() -> Toast.makeText(activity, s, Toast.LENGTH_SHORT).show());
	}

	private void onMicTap() {
		if (SherpaVoice.isCapturing()) {
			SherpaVoice.stopCapture();
			return;
		}
		if (listening) {
			stopListening();
			return;
		}
		if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(
				activity, Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(activity,
					new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
			toast("Permita o microfone e toque de novo");
			return;
		}
		if (SherpaVoice.isSttReady()) {
			startOfflineListening();
			return;
		}
		// Modelos ainda baixando: usa Google por enquanto
		if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
			toast("Reconhecimento de voz indisponível");
			return;
		}
		startListening();
	}

	// Ouve com whisper no aparelho: toca pra começar, toca pra parar
	// (ou para sozinho no silêncio).
	private void startOfflineListening() {
		listening = true;
		main.post(() -> micButton.setBackgroundColor(0xAA3355AA));
		toast("Ouvindo offline... toque pra parar");
		SherpaVoice.startCapture(samples -> {
			listening = false;
			main.post(() -> micButton.setBackgroundColor(0xAA222222));
			if (samples == null || samples.length < SAMPLE_FLOOR) {
				toast("Não ouvi nada, fala de novo");
				return;
			}
			toast("Entendendo...");
			new Thread(() -> {
				String heard = SherpaVoice.recognize(samples);
				if (heard == null || heard.isEmpty()) {
					toast("Não entendi, fala de novo");
					return;
				}
				toast("Ouvi: " + heard);
				sendVoice(heard);
			}).start();
		});
	}

	private static final int SAMPLE_FLOOR = 8000; // <0.5s ignora

	private void startListening() {
		try {
			if (recognizer == null) {
				recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
				recognizer.setRecognitionListener(new RecognitionListener() {
					@Override public void onReadyForSpeech(Bundle p) {
						micButton.setBackgroundColor(0xAA33AA33);
					}
					@Override public void onBeginningOfSpeech() {}
					@Override public void onRmsChanged(float v) {}
					@Override public void onBufferReceived(byte[] b) {}
					@Override public void onEndOfSpeech() {}
					@Override public void onError(int e) {
						listening = false;
						main.post(() -> micButton.setBackgroundColor(0xAA222222));
					}
					@Override public void onPartialResults(Bundle r) {}
					@Override public void onEvent(int t, Bundle b) {}
					@Override public void onResults(Bundle results) {
						listening = false;
						main.post(() -> micButton.setBackgroundColor(0xAA222222));
						ArrayList<String> list = results.getStringArrayList(
								SpeechRecognizer.RESULTS_RECOGNITION);
						if (list != null && !list.isEmpty()) {
							String heard = list.get(0);
							toast("Ouvi: " + heard);
							sendVoice(heard);
						}
					}
				});
			}
			Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
			intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
					RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
			intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
			intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o assistente");
			recognizer.startListening(intent);
			listening = true;
		} catch (Exception e) {
			toast("Mic: " + e.getMessage());
		}
	}

	private void stopListening() {
		try {
			if (recognizer != null) recognizer.stopListening();
		} catch (Exception ignored) {}
		listening = false;
	}

	private void showConfigDialog() {
		LinearLayout box = new LinearLayout(activity);
		box.setOrientation(LinearLayout.VERTICAL);
		final EditText serverEt = new EditText(activity);
		serverEt.setHint("Servidor (http://ip:8090)");
		serverEt.setText(getServer(activity));
		final EditText playerEt = new EditText(activity);
		playerEt.setHint("Nome do jogador");
		playerEt.setText(getPlayer(activity));
		box.addView(serverEt);
		box.addView(playerEt);
		new AlertDialog.Builder(activity)
				.setTitle("JoaoCraft voz")
				.setView(box)
				.setPositiveButton("Salvar", (d, w) -> {
					SharedPreferences.Editor ed = activity
							.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit();
					ed.putString("server", serverEt.getText().toString().trim());
					ed.putString("player", playerEt.getText().toString().trim());
					ed.apply();
					toast("Salvo!");
				})
				.setNegativeButton("Cancelar", null)
				.show();
	}

	private void sendVoice(final String text) {
		new Thread(() -> {
			try {
				JSONObject body = new JSONObject();
				body.put("player", getPlayer(activity));
				body.put("text", text);
				postJson(getServer(activity) + "/voice", body.toString());
			} catch (Exception e) {
				toast("Falha ao enviar voz");
			}
		}).start();
	}

	private void initTts() {
		try {
			tts = new TextToSpeech(activity, status -> {
				if (status == TextToSpeech.SUCCESS && tts != null) {
					int r = tts.setLanguage(new Locale("pt", "BR"));
					if (r == TextToSpeech.LANG_MISSING_DATA
							|| r == TextToSpeech.LANG_NOT_SUPPORTED) {
						tts.setLanguage(Locale.getDefault());
					}
					tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
						@Override public void onStart(String id) {}
						@Override public void onDone(String id) {}
						@Override public void onError(String id) {}
					});
				}
			});
		} catch (Exception ignored) {}
	}

	private void speak(String text) {
		if (text == null || text.isEmpty()) return;
		// Voz neural offline primeiro; Google TTS como reserva
		if (SherpaVoice.isTtsReady()) {
			new Thread(() -> SherpaVoice.play(text)).start();
			return;
		}
		if (tts == null) return;
		try {
			tts.speak(text, TextToSpeech.QUEUE_ADD, null, "joao" + System.currentTimeMillis());
		} catch (Exception ignored) {}
	}

	private void startReplyPolling() {
		polling = true;
		new Thread(() -> {
			while (polling) {
				try {
					Thread.sleep(4000);
					if (!polling) break;
					String url = getServer(activity) + "/voice/reply?player="
							+ URLEncoder.encode(getPlayer(activity), "UTF-8")
							+ "&since=" + lastReplyTs;
					String resp = getJson(url);
					if (resp == null) continue;
					JSONObject o = new JSONObject(resp);
					JSONArray arr = o.optJSONArray("replies");
					if (arr == null) continue;
					for (int i = 0; i < arr.length(); i++) {
						JSONObject m = arr.getJSONObject(i);
						long ts = m.optLong("ts", 0);
						String text = m.optString("text", "");
						if (ts > lastReplyTs) lastReplyTs = ts;
						if (!text.isEmpty()) speak(text);
					}
				} catch (Exception ignored) {}
			}
		}).start();
	}

	private void postJson(String urlStr, String json) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
		try {
			c.setConnectTimeout(10000);
			c.setReadTimeout(15000);
			c.setRequestMethod("POST");
			c.setRequestProperty("Content-Type", "application/json");
			c.setDoOutput(true);
			try (OutputStream os = c.getOutputStream()) {
				os.write(json.getBytes("UTF-8"));
			}
			InputStream in = c.getResponseCode() < 400
					? c.getInputStream() : c.getErrorStream();
			if (in != null) {
				BufferedReader br = new BufferedReader(new InputStreamReader(in));
				while (br.readLine() != null) { /* drain */ }
				br.close();
			}
		} finally {
			c.disconnect();
		}
	}

	private String getJson(String urlStr) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(urlStr).openConnection();
			c.setConnectTimeout(8000);
			c.setReadTimeout(10000);
			if (c.getResponseCode() != 200) return null;
			BufferedReader br = new BufferedReader(
					new InputStreamReader(c.getInputStream()));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) sb.append(line);
			br.close();
			return sb.toString();
		} catch (Exception e) {
			return null;
		} finally {
			if (c != null) c.disconnect();
		}
	}
}
