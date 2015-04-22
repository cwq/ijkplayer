package tv.danmaku.ijk.media.demo;

import android.app.Activity;
import android.app.ListActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * 增加一个测试用例列表
 */
public class SampleListActivity extends ListActivity {
    private static final String GET_URL = "https://newapi.meipai.com/medias/square_timeline.json";

    ArrayAdapter<String> video_list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        video_list = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        setListAdapter(video_list);

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpClient httpClient = new DefaultHttpClient();
                HttpGet httpGet = new HttpGet(GET_URL);
                HttpResponse httpResponse = null;
                try {
                    httpResponse = httpClient.execute(httpGet);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                assert httpResponse != null;

                if (httpResponse.getStatusLine().getStatusCode() == 200) {
                    try {
                        String result = EntityUtils.toString(httpResponse.getEntity());
//                        Log.e("JAVAN", result);
                        try {
                            JSONArray jsonArray = new JSONArray(result);
                            int length = jsonArray != null ? jsonArray.length() : 0;
                            for (int i = 0; i < length; i++) {
                                do {
                                    JSONObject object = (JSONObject) jsonArray.opt(i);
                                    if (object == null) break;

                                    JSONObject media = object.getJSONObject("media");
                                    if (media == null) break;

                                    final String video = media.getString("video");

                                    if (video == null) break;

                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            video_list.add(video);
                                        }
                                    });

                                } while (false);

                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);

        Intent intent = new Intent(SampleListActivity.this, VideoPlayerActivity.class);
        intent.setData(Uri.parse(video_list.getItem(position)));
        startActivity(intent);
    }
}
