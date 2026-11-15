package tech.wetech.admin3.ocrApi;

import okhttp3.*;
import org.json.JSONObject;

import java.io.*;

class GenaAPIToken {

  static final OkHttpClient HTTP_CLIENT = new OkHttpClient().newBuilder().build();

  public static void main(String []args) throws IOException{
    MediaType mediaType = MediaType.parse("application/json");
    RequestBody body = RequestBody.create(mediaType, "");
    Request request = new Request.Builder()
      .url("https://aip.baidubce.com/oauth/2.0/token?client_id=DSSsCG6H8V551EOdc6YVoKlu&client_secret=wLbzjFt0seNJdEkS5FHBfru7V6g7jWzZ&grant_type=client_credentials")
      .method("POST", body)
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")
      .build();
    Response response = HTTP_CLIENT.newCall(request).execute();
    System.out.println(response.body().string());

  }


}
