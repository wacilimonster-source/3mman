package com.m3man.ui.mman9video.user;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;

import com.devbrackets.android.exomedia.util.ResourceUtil;
import com.qmuiteam.qmui.util.QMUIKeyboardHelper;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.constants.Keys;
import com.m3man.constants.KeysActivityRequestResultCode;
import com.m3man.data.DataManager;
import com.m3man.data.model.User;
import com.m3man.ui.MvpActivity;
import com.m3man.ui.mman9video.favorite.FavoriteActivity;
import com.m3man.ui.mman9video.search.SearchActivity;
import com.m3man.ui.setting.SettingActivity;
import com.m3man.utils.CaptchaOcr;
import com.m3man.utils.DialogUtils;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * @author flymegoc
 */
public class UserLoginActivity extends MvpActivity<UserView, UserPresenter> implements UserView {

    private static final String TAG = UserLoginActivity.class.getSimpleName();
    @BindView(R.id.et_account)
    EditText etAccount;
    @BindView(R.id.et_password)
    EditText etPassword;
    @BindView(R.id.et_captcha)
    EditText etCaptcha;
    @BindView(R.id.wb_captcha)
    ImageView captchaImageView;
    @BindView(R.id.bt_user_login)
    Button btUserLogin;
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.cb_remember_password)
    CheckBox cbRememberPassword;

    private AlertDialog alertDialog;
    private String username;
    private String password;
    private int loginForAction;

    // 验证码自动识别（OCR）
    private CaptchaOcr captchaOcr;
    private Bitmap currentCaptchaBitmap;
    private volatile boolean ocrInitializing = false;


    @Inject
    UserPresenter userPresenter;

    @Inject
    DataManager dataManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_login);
        ButterKnife.bind(this);
        initToolBar(toolbar);

        loginForAction = getIntent().getIntExtra(Keys.KEY_INTENT_LOGIN_FOR_ACTION, 0);
        if (!TextUtils.isEmpty(presenter.getVideo9MmanAddress())) {
            loadCaptcha();
        }
        btUserLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                username = etAccount.getText().toString().trim();
                password = etPassword.getText().toString().trim();
                String captcha = etCaptcha.getText().toString().trim();
                login(username, password, captcha);
            }
        });
        captchaImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCaptcha();
            }
        });

//        cbAutoLogin.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                if (cbAutoLogin.isChecked()) {
//                    cbAutoLogin.setChecked(true);
//                    cbRememberPassword.setChecked(true);
//                } else {
//                    cbAutoLogin.setChecked(false);
//                }
//            }
//        });

        cbRememberPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cbRememberPassword.isChecked()) {
                    cbRememberPassword.setChecked(true);
                } else {
                    cbRememberPassword.setChecked(false);
                    // cbAutoLogin.setChecked(false);
                }
            }
        });

        alertDialog = DialogUtils.initLoadingDialog(this, "登录中，请稍后...");
        setUpUserInfo();

        // 启动验证码 OCR 引擎初始化（子线程），加载成功后自动识别填入
        if (!TextUtils.isEmpty(presenter.getVideo9MmanAddress())) {
            prepareCaptchaOcr();
        }

        if (TextUtils.isEmpty(presenter.getVideo9MmanAddress())) {
            showNeedSetAddressFirstDialog();
        }
    }

    private void setUpUserInfo() {
        username = presenter.getUserName();
        password = presenter.getPassword();
        if (!TextUtils.isEmpty(password)) {
            cbRememberPassword.setChecked(true);
        }
        //boolean isAutoLogin = presenter.isAutoLogin();
        //cbAutoLogin.setChecked(isAutoLogin);

        etAccount.setText(username);
        etPassword.setText(password);
    }

    private void login(String username, String password, String captcha) {

        if (TextUtils.isEmpty(username)) {
            showMessage("请填写用户名", TastyToast.INFO);
            return;
        }
        if (TextUtils.isEmpty(password)) {
            showMessage("请填写密码", TastyToast.INFO);
            return;
        }
        if (TextUtils.isEmpty(captcha)) {
            showMessage("请填写验证码", TastyToast.INFO);
            return;
        }
        QMUIKeyboardHelper.hideKeyboard(getCurrentFocus());
        presenter.login(username, password, captcha);
    }

    /**
     * 加载验证码，目前似乎是非必须，不填也是可以登录的
     */
    private void loadCaptcha() {
        presenter.loadCaptcha();
    }

    @NonNull
    @Override
    public UserPresenter createPresenter() {
        return userPresenter;
    }

    private void showNeedSetAddressFirstDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.MyDialogTheme);
        builder.setTitle("温馨提示");
        builder.setMessage("还未设置91mman视频地址,无法登录或注册，现在去设置？");
        builder.setPositiveButton("去设置", (dialog, which) -> {
            Intent intent = new Intent(context, SettingActivity.class);
            startActivityWithAnimation(intent);
            finish();
        });
        builder.setNegativeButton("退出", (dialog, which) -> {
            dialog.dismiss();
            onBackPressed();
        });
        builder.setCancelable(false);
        builder.show();
    }


    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onDestroy() {
        if (captchaOcr != null) {
            captchaOcr.recycle();
            captchaOcr = null;
        }
        if (currentCaptchaBitmap != null && !currentCaptchaBitmap.isRecycled()) {
            currentCaptchaBitmap.recycle();
            currentCaptchaBitmap = null;
        }
        super.onDestroy();
    }

    @Override
    public void loginSuccess(User user) {

        presenter.saveUserInfoPrf(username, password, cbRememberPassword.isChecked(), false);
        showMessage("登录成功", TastyToast.SUCCESS);
        switchWhereToGo();
    }


    private void switchWhereToGo() {
        switch (loginForAction) {
            case KeysActivityRequestResultCode.LOGIN_ACTION_FOR_LOOK_MY_FAVORITE:
                Intent intent = new Intent(this, FavoriteActivity.class);
                startActivityWithAnimation(intent);
                finish();
                break;
            case KeysActivityRequestResultCode.LOGIN_ACTION_FOR_SEARCH_91PRON_VIDEO:
                Intent intentSearch = new Intent(this, SearchActivity.class);
                startActivityWithAnimation(intentSearch);
                finish();
                break;
            case KeysActivityRequestResultCode.LOGIN_ACTION_FOR_GET_UID:
                setResult(KeysActivityRequestResultCode.RESULT_CODE_FOR_REFRESH_GET_UID);
                onBackPressed();
                break;
            case KeysActivityRequestResultCode.LOGIN_ACTION_FOR_LOOK_AUTHOR_VIDEO:
                setResult(KeysActivityRequestResultCode.RESULT_FOR_LOOK_AUTHOR_VIDEO);
                onBackPressed();
                break;
            default:
                setResult(RESULT_OK);
                onBackPressed();
        }
    }

    @Override
    public void loginError(String message) {
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void registerSuccess(User user) {

    }

    @Override
    public void registerFailure(String message) {

    }

    @Override
    public void loadCaptchaSuccess(Bitmap bitmap) {
        currentCaptchaBitmap = bitmap;
        captchaImageView.setImageBitmap(bitmap);
        // 自动识别验证码并填入输入框（识别失败时由用户手动输入）
        recognizeCaptcha(bitmap);
    }

    /**
     * 在子线程初始化 OCR 引擎；初始化完成后若已有验证码图片则立即识别。
     */
    private void prepareCaptchaOcr() {
        if (captchaOcr != null || ocrInitializing) {
            return;
        }
        ocrInitializing = true;
        Observable.fromCallable(() -> {
            captchaOcr = new CaptchaOcr(UserLoginActivity.this);
            return captchaOcr.initEngine();
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ready -> {
                    ocrInitializing = false;
                    if (ready && currentCaptchaBitmap != null) {
                        recognizeCaptcha(currentCaptchaBitmap);
                    } else if (!ready) {
                        showMessage("验证码识别引擎初始化失败，请手动输入", TastyToast.INFO);
                    }
                }, e -> {
                    ocrInitializing = false;
                    showMessage("验证码识别引擎初始化失败，请手动输入", TastyToast.INFO);
                });
    }

    /**
     * 对已加载的验证码图片进行 OCR 识别并回填到输入框。
     */
    private void recognizeCaptcha(final Bitmap bitmap) {
        if (captchaOcr == null || !captchaOcr.isReady()) {
            prepareCaptchaOcr();
            return;
        }
        Observable.fromCallable(() -> captchaOcr.recognize(bitmap))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (!TextUtils.isEmpty(result)) {
                        etCaptcha.setText(result);
                        etCaptcha.setSelection(result.length());
                    } else {
                        showMessage("验证码识别失败，请手动输入", TastyToast.INFO);
                    }
                }, e -> showMessage("验证码识别失败，请手动输入", TastyToast.ERROR));
    }

    @Override
    public void loadCaptchaFailure(String errorMessage, int code) {
        captchaImageView.setImageDrawable(ResourceUtil.getDrawable(this, R.drawable.ic_refresh));
        showError("无法加载验证码,点击刷新重试");
    }

    @Override
    public void showError(String message) {
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        if (alertDialog == null) {
            return;
        }
        alertDialog.show();
    }

    @Override
    public void showContent() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.login, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_user_register) {
            new QMUIDialog.MessageDialogBuilder(this)
                    .setMessage("注册功能已停止支持，请去9*mman官网注册，之后再来登录！")
                    .addAction("知道了", new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
