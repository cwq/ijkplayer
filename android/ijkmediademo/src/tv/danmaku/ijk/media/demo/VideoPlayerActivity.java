/*
 * Copyright (C) 2013 Zhang Rui <bbcallen@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tv.danmaku.ijk.media.demo;

import java.io.File;
import java.io.IOException;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import tv.danmaku.ijk.media.widget.MediaController;
import tv.danmaku.ijk.media.widget.VideoView;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;

public class VideoPlayerActivity extends Activity {
	private VideoView mVideoView;
	private View mBufferingIndicator;
	private MediaController mMediaController;

	private String mVideoPath;
	private StreamProxy proxy;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_player);

		mVideoPath = "http://mvvideo1.meitudata.com/5517ac4679dbe8010.mp4";
//		mVideoPath = "http://mvvideo1.meitudata.com/5524c39336c132760.mp4";
//		new DownloadTask().execute(mVideoPath);
//		mVideoPath = "/mnt/sdcard/kaka.mp4";
		if (proxy == null) {
			try {
				proxy = new StreamProxy(mVideoPath, new StreamProxy.ProxyCallback() {
					@Override
					public void onError() {
						Log.d("JAVAN", "onError");
					}

					@Override
					public void onNetError() {
						Log.d("JAVAN", "onNetError");
					}
				});
				proxy.init();
				proxy.start();
			} catch (Exception e) {
				proxy = null;
			}
		}

		String playUrl = mVideoPath;
		playUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), mVideoPath);
		Log.d("JAVAN", "playUrl : " + playUrl);

		Intent intent = getIntent();
		String intentAction = intent.getAction();
		if (!TextUtils.isEmpty(intentAction)
				&& intentAction.equals(Intent.ACTION_VIEW)) {
			mVideoPath = intent.getDataString();
		}

		if (TextUtils.isEmpty(mVideoPath)) {
			mVideoPath = new File(Environment.getExternalStorageDirectory(),
					"download/test.mp4").getAbsolutePath();
		}

		mBufferingIndicator = findViewById(R.id.buffering_indicator);
		mMediaController = new MediaController(this);

		mVideoView = (VideoView) findViewById(R.id.video_view);
		mVideoView.setMediaController(mMediaController);
		mVideoView.setMediaBufferingIndicator(mBufferingIndicator);
		mVideoView.setVideoPath(playUrl);
		mVideoView.requestFocus();
		mVideoView.start();
	}

//	private class DownloadTask extends AsyncTask<String, Long, File> {
//		protected File doInBackground(String... urls) {
//			File file = null;
//			try {
//				HttpRequestUtil request =  HttpRequestUtil.get(urls[0]);
//				if (request.ok()) {
//					file = File.createTempFile("download", ".tmp");
//					request.receive(file);
//					publishProgress(file.length());
//				}
//			} catch (HttpRequestUtil.HttpRequestException exception) {
//			} catch (IOException e) {
//				e.printStackTrace();
//			} finally {
//				return file;
//			}
//		}
//
//		protected void onProgressUpdate(Long... progress) {
//			Log.d("MyApp", "Downloaded bytes: " + progress[0]);
//		}
//
//		protected void onPostExecute(File file) {
//			if (file != null)
//				Log.d("MyApp", "Downloaded file to: " + file.getAbsolutePath());
//			else
//				Log.d("MyApp", "Download failed");
//		}
//	}
}
