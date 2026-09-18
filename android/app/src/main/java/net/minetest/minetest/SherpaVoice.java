package net.minetest.minetest;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.Keep;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * JoaoCraft offline voice engine (sherpa-onnx, Apache 2.0).
 * Speech-to-text: whisper-base multilíngue (pt) direto no aparelho.
 * Text-to-speech: Piper pt-BR (voz cadu) direto no aparelho.
 * Modelos baixados uma vez (Wi-Fi) para getExternalFilesDir("joaocraft-voz").
 * Sem conta Google, sem nuvem, funciona offline depois de baixar.
 */
@Keep
public class SherpaVoice {
	private static final String TAG = "JoaoCraftVoz";
	private static final int SAMPLE_RATE = 16000;

	private static final String WHISPER_REPO =
			"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/";
	private static final String PIPER_REPO =
			"https://huggingface.co/csukuangfj/vits-piper-pt_BR-cadu-medium/resolve/main/";

	private static OfflineRecognizer recognizer;
	private static OfflineTts tts;
	private static boolean downloading = false;

	public interface ProgressCb {
		void onProgress(String file, int percent);
		void onDone(boolean ok, String msg);
	}

	private static File baseDir(Context ctx) {
		File d = new File(ctx.getExternalFilesDir(null), "joaocraft-voz");
		if (!d.exists()) d.mkdirs();
		return d;
	}

	public static synchronized boolean isSttReady() {
		return recognizer != null;
	}

	public static synchronized boolean isTtsReady() {
		return tts != null;
	}

	public static synchronized boolean isDownloading() {
		return downloading;
	}

	/** Tenta carregar modelos já baixados. Retorna true se STT+TTS prontos. */
	public static synchronized boolean tryLoad(Context ctx) {
		try {
			File base = baseDir(ctx);
			File enc = new File(base, "whisper/base-encoder.int8.onnx");
			File dec = new File(base, "whisper/base-decoder.int8.onnx");
			File tok = new File(base, "whisper/base-tokens.txt");
			if (enc.exists() && dec.exists() && tok.exists() && recognizer == null) {
				int threads = Math.max(1, Math.min(2,
						Runtime.getRuntime().availableProcessors()));
				OfflineWhisperModelConfig whisper =
						OfflineWhisperModelConfig.builder()
								.setEncoder(enc.getAbsolutePath())
								.setDecoder(dec.getAbsolutePath())
								.setLanguage("pt")
								.setTask("transcribe")
								.build();
				OfflineModelConfig model = OfflineModelConfig.builder()
						.setWhisper(whisper)
						.setTokens(tok.getAbsolutePath())
						.setNumThreads(threads)
						.setDebug(false)
						.build();
				OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
						.setOfflineModelConfig(model)
						.setDecodingMethod("greedy_search")
						.build();
				recognizer = new OfflineRecognizer(config);
			}
		} catch (Exception e) {
			Log.e(TAG, "STT load falhou", e);
			if (recognizer != null) {
				try {
					recognizer.release();
				} catch (Exception ignored) {}
				recognizer = null;
			}
		}
		try {
			File onnx = new File(baseDir(ctx), "piper/pt_BR-cadu-medium.onnx");
			File json = new File(baseDir(ctx), "piper/pt_BR-cadu-medium.onnx.json");
			File tokens = new File(baseDir(ctx), "piper/tokens.txt");
			File espeak = new File(baseDir(ctx), "piper/espeak-ng-data");
			if (onnx.exists() && json.exists() && tokens.exists()
					&& espeak.exists() && tts == null) {
				// dataDir: tenta o dir do modelo primeiro, cai para o espeak direto
				String dataDir = new File(baseDir(ctx), "piper").getAbsolutePath();
				OfflineTtsVitsModelConfig vits;
				try {
					vits = OfflineTtsVitsModelConfig.builder()
							.setModel(onnx.getAbsolutePath())
							.setTokens(tokens.getAbsolutePath())
							.setDataDir(dataDir)
							.setNoiseScale(0.667f)
							.setNoiseScaleW(0.8f)
							.setLengthScale(1.0f)
							.build();
					tts = new OfflineTts(OfflineTtsConfig.builder()
							.setModel(OfflineTtsModelConfig.builder()
									.setVits(vits)
									.setNumThreads(2)
									.setDebug(false)
									.build())
							.setMaxNumSentences(10)
							.build());
				} catch (Exception e1) {
					Log.w(TAG, "TTS dataDir modelo falhou, tentando espeak direto", e1);
					vits = OfflineTtsVitsModelConfig.builder()
							.setModel(onnx.getAbsolutePath())
							.setTokens(tokens.getAbsolutePath())
							.setDataDir(espeak.getAbsolutePath())
							.build();
					tts = new OfflineTts(OfflineTtsConfig.builder()
							.setModel(OfflineTtsModelConfig.builder()
									.setVits(vits)
									.setNumThreads(2)
									.setDebug(false)
									.build())
							.build());
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "TTS load falhou", e);
			if (tts != null) {
				try {
					tts.release();
				} catch (Exception ignored) {}
				tts = null;
			}
		}
		return recognizer != null && tts != null;
	}

	private static class Job {
		final String url;
		final File dest;
		Job(String u, File d) {
			url = u;
			dest = d;
		}
	}

	/** Baixa o que falta (uma vez). Roda em thread própria por quem chamar. */
	public static synchronized void ensureModels(Context ctx, ProgressCb cb) {
		if (isSttReady() && isTtsReady()) {
			cb.onDone(true, "pronto");
			return;
		}
		if (downloading) return;
		downloading = true;
		new Thread(() -> {
			try {
				File base = baseDir(ctx);
				List<Job> jobs = new ArrayList<>();
				jobs.add(new Job(WHISPER_REPO + "base-encoder.int8.onnx",
						new File(base, "whisper/base-encoder.int8.onnx")));
				jobs.add(new Job(WHISPER_REPO + "base-decoder.int8.onnx",
						new File(base, "whisper/base-decoder.int8.onnx")));
				jobs.add(new Job(WHISPER_REPO + "base-tokens.txt",
						new File(base, "whisper/base-tokens.txt")));
				jobs.add(new Job(PIPER_REPO + "pt_BR-cadu-medium.onnx",
						new File(base, "piper/pt_BR-cadu-medium.onnx")));
				jobs.add(new Job(PIPER_REPO + "pt_BR-cadu-medium.onnx.json",
						new File(base, "piper/pt_BR-cadu-medium.onnx.json")));
				jobs.add(new Job(PIPER_REPO + "tokens.txt",
						new File(base, "piper/tokens.txt")));
				// espeak-ng-data: só o essencial do pt-BR (o resto é de outros idiomas)
				String[] espeakFiles = {"phondata", "phonindex", "phontab",
						"intonations", "phondata-manifest", "pt_dict",
						"lang/roa/pt", "lang/roa/pt-BR"};
				for (String f : espeakFiles) {
					jobs.add(new Job(PIPER_REPO + "espeak-ng-data/" + f,
							new File(base, "piper/espeak-ng-data/" + f)));
				}
				int done = 0;
				for (Job j : jobs) {
					if (j.dest.exists() && j.dest.length() > 1024) {
						done++;
						continue;
					}
					download(j.url, j.dest);
					done++;
					cb.onProgress(j.dest.getName(), (done * 100) / jobs.size());
				}
				boolean ok = tryLoad(ctx);
				cb.onDone(ok, ok ? "voz offline pronta" : "falha ao ativar, usando Google");
			} catch (Exception e) {
				Log.e(TAG, "download falhou", e);
				cb.onDone(false, "sem internet? usando Google por enquanto");
			} finally {
				downloading = false;
			}
		}).start();
	}

	private static void download(String urlStr, File dest) throws Exception {
		if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
		File tmp = new File(dest.getAbsolutePath() + ".tmp");
		HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
		c.setConnectTimeout(20000);
		c.setReadTimeout(60000);
		c.connect();
		if (c.getResponseCode() != 200) {
			throw new Exception("HTTP " + c.getResponseCode() + " " + urlStr);
		}
		try (InputStream in = new BufferedInputStream(c.getInputStream());
			 OutputStream out = new FileOutputStream(tmp)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		}
		c.disconnect();
		if (!tmp.renameTo(dest)) {
			throw new Exception("rename " + dest.getName());
		}
	}

	/** Reconhece amostras 16kHz mono. Roda fora da UI thread. */
	public static synchronized String recognize(float[] samples) {
		if (recognizer == null) return "";
		OfflineStream stream = null;
		try {
			stream = recognizer.createStream();
			stream.acceptWaveform(samples, SAMPLE_RATE);
			recognizer.decode(stream);
			String text = recognizer.getResult(stream).getText();
			return text == null ? "" : text.trim();
		} catch (Exception e) {
			Log.e(TAG, "recognize falhou", e);
			return "";
		} finally {
			if (stream != null) {
				try {
					stream.release();
				} catch (Exception ignored) {}
			}
		}
	}

	/** Fala o texto no alto-falante. Roda fora da UI thread. */
	public static void play(String text) {
		OfflineTts engine;
		synchronized (SherpaVoice.class) {
			engine = tts;
		}
		if (engine == null || text == null || text.isEmpty()) return;
		try {
			GeneratedAudio audio = engine.generate(text, 0, 1.0f);
			if (audio == null) return;
			float[] samples = audio.getSamples();
			int sampleRate = audio.getSampleRate();
			if (samples == null || samples.length == 0) return;
			int bufSize = AudioTrack.getMinBufferSize(sampleRate,
					AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_FLOAT);
			AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC,
					sampleRate, AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_FLOAT,
					Math.max(bufSize, samples.length * 4),
					AudioTrack.MODE_STREAM);
			track.play();
			int off = 0;
			while (off < samples.length) {
				int chunk = Math.min(4096, samples.length - off);
				track.write(samples, off, chunk, AudioTrack.WRITE_BLOCKING);
				off += chunk;
			}
			track.stop();
			track.release();
		} catch (Exception e) {
			Log.e(TAG, "play falhou", e);
		}
	}

	public interface CaptureCb {
		void onText(float[] samples);
	}

	private static AudioRecord recorder;
	private static Thread captureThread;
	private static volatile boolean capturing = false;

	/** Grava do mic até parar, silêncio de 1.2s ou 12s. Chama cb com o áudio. */
	public static synchronized void startCapture(CaptureCb cb) {
		if (capturing) return;
		int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
				AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
				SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, 32000));
		if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return;
		capturing = true;
		recorder.startRecording();
		captureThread = new Thread(() -> {
			ArrayList<Short> all = new ArrayList<>(SAMPLE_RATE * 6);
			short[] buf = new short[1600]; // 100ms
			long silentMs = 0;
			long totalMs = 0;
			boolean heard = false;
			while (capturing && totalMs < 12000) {
				int n = recorder.read(buf, 0, buf.length);
				if (n <= 0) continue;
				double sum = 0;
				for (int i = 0; i < n; i++) {
					all.add(buf[i]);
					sum += buf[i] * buf[i];
				}
				double rms = Math.sqrt(sum / n) / 32768.0;
				totalMs += 100;
				if (rms > 0.02) {
					heard = true;
					silentMs = 0;
				} else if (heard) {
					silentMs += 100;
					if (silentMs >= 1200) break;
				}
			}
			stopCaptureInternal();
			float[] samples = new float[all.size()];
			for (int i = 0; i < all.size(); i++) samples[i] = all.get(i) / 32768.0f;
			cb.onText(samples);
		});
		captureThread.start();
	}

	public static synchronized void stopCapture() {
		capturing = false;
	}

	private static synchronized void stopCaptureInternal() {
		capturing = false;
		try {
			if (recorder != null) {
				recorder.stop();
				recorder.release();
			}
		} catch (Exception ignored) {}
		recorder = null;
	}

	public static synchronized boolean isCapturing() {
		return capturing;
	}
}
