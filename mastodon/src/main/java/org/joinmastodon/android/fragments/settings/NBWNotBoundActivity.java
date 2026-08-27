package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NBWNotBoundActivity extends Activity {
    private static final OkHttpClient httpClient = new OkHttpClient();
    private String nbwToken;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        UiUtils.setUserPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nbw_not_bound);

        nbwToken=getIntent().getStringExtra("nbw_token");

        View rootView=findViewById(android.R.id.content);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){
            rootView.setOnApplyWindowInsetsListener((v, insets)->{
                int statusBar=insets.getInsets(WindowInsets.Type.statusBars()).top;
                rootView.setPadding(0, statusBar, 0, 0);
                return insets;
            });
        }else{
            rootView.setPadding(0, getStatusBarHeight(), 0, 0);
        }

        ImageView backBtn=findViewById(R.id.btn_back);
        backBtn.setOnClickListener(v->finish());

        Button registerBtn=findViewById(R.id.btn_register);
        registerBtn.setOnClickListener(v->loadNBWInfoForRegister());

        Button loginExistingBtn=findViewById(R.id.btn_login_existing);
        loginExistingBtn.setOnClickListener(v->openLoginPage());
    }

    private void loadNBWInfoForRegister(){
        if(nbwToken==null || nbwToken.isEmpty()){
            Toast.makeText(this, "宝宝新天地授权信息缺失，请重新授权", Toast.LENGTH_LONG).show();
            return;
        }

        ProgressDialog progress=new ProgressDialog(this);
        progress.setMessage("正在读取宝宝新天地资料...");
        progress.setCancelable(false);
        progress.show();

        String json=new Gson().toJson(new UserInfoBody(nbwToken));
        RequestBody body=RequestBody.create(MediaType.parse("application/json"), json);
        httpClient.newCall(new Request.Builder()
                .url("https://abdl-space.top/api/auth/nbw/user-info")
                .post(body)
                .build())
            .enqueue(new okhttp3.Callback(){
                @Override
                public void onFailure(Call call, IOException e){
                    runOnUiThread(()->{
                        progress.dismiss();
                        Toast.makeText(NBWNotBoundActivity.this, "网络错误: "+e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException{
                    String responseBody=response.body()!=null ? response.body().string() : "";
                    runOnUiThread(()->{
                        progress.dismiss();
                        if(!response.isSuccessful()){
                            Toast.makeText(NBWNotBoundActivity.this, "读取资料失败: "+responseBody, Toast.LENGTH_LONG).show();
                            return;
                        }
                        UserInfoResponse info=new Gson().fromJson(responseBody, UserInfoResponse.class);
                        if(info==null){
                            Toast.makeText(NBWNotBoundActivity.this, "宝宝新天地资料无效", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if(info.email_registered){
                            Toast.makeText(NBWNotBoundActivity.this, "该邮箱已注册 ABDL Space，请登录已有账号后绑定", Toast.LENGTH_LONG).show();
                            openLoginPage();
                            return;
                        }
                        openRegisterPage(info);
                    });
                }
            });
    }

    private void openLoginPage(){
        Intent intent=new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openRegisterPage(UserInfoResponse info){
        Intent intent=new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("open_nbw_register", true);
        intent.putExtra("email", info.email);
        intent.putExtra("prefill_username", info.username);
        intent.putExtra("nbw_token", nbwToken);
        intent.putExtra("nbw_uid", info.uid);
        intent.putExtra("nbw_username", info.username);
        startActivity(intent);
        Toast.makeText(this, "已帮你自动填写部分来自宝宝新天地的账号信息，请完善密码，也可以修改预填信息", Toast.LENGTH_LONG).show();
        finish();
    }

    private int getStatusBarHeight(){
        int resourceId=getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId>0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    private static class UserInfoBody{
        public String nbw_token;
        public UserInfoBody(String nbwToken){
            this.nbw_token=nbwToken;
        }
    }

    private static class UserInfoResponse{
        public String uid;
        public String username;
        public String email;
        public boolean email_registered;
    }
}
