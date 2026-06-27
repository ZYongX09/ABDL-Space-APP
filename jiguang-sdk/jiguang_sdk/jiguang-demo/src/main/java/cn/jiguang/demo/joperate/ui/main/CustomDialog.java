package cn.jiguang.demo.joperate.ui.main;

import android.app.Dialog;
import android.content.Context;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import cn.jiguang.demo.R;


/**
 * 作者 Aaron Zhao
 * 时间 2015/9/16 11:21
 * 名称 CustomDialog.java 描述
 */
public class CustomDialog extends Dialog {
    /* Constructor */
    private CustomDialog(Context context) {
        super(context);
    }

    private CustomDialog(Context context, int themeResId) {
        super(context, themeResId);
    }

    private CustomDialog(Context context, boolean cancelable, OnCancelListener cancelListener) {
        super(context, cancelable, cancelListener);
    }

    /* Builder */
    public static class Builder {
        private View view;
        private ImageView image_icon;
        private TextView text_title, text_warning;
        private Button button_cancel, button_ok;
        private EditText edit;

        private View mLayout;
        private View.OnClickListener mButtonCancelClickListener;
        private View.OnClickListener mButtonConfirmClickListener;

        private CustomDialog mDialog;

        public Builder(Context context) {
            mDialog = new CustomDialog(context, R.style.custom_dialog);
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            // 加载布局文件
            mLayout = inflater.inflate(R.layout.joperate_dialog_normal_layout, null, false);
            // 添加布局文件到 Dialog
            mDialog.addContentView(mLayout, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            view = mLayout.findViewById(R.id.view);
            image_icon = mLayout.findViewById(R.id.image_icon);
            text_title = mLayout.findViewById(R.id.text_title);
            text_warning = mLayout.findViewById(R.id.text_warning);
            button_cancel = mLayout.findViewById(R.id.button_cancel);
            button_ok = mLayout.findViewById(R.id.button_ok);
            edit = mLayout.findViewById(R.id.edit);
        }

        public Builder setIocn(int resid) {
            view.setVisibility(View.VISIBLE);
            image_icon.setBackgroundResource(resid);
            image_icon.setVisibility(View.VISIBLE);
            return this;
        }

        /**
         * 设置 Dialog 标题
         */
        public Builder setTitle(String title) {
            text_title.setText(title);
            text_title.setVisibility(View.VISIBLE);
            return this;
        }


        public Builder setHint(String title) {
            edit.setHint(title);
            edit.setVisibility(View.VISIBLE);
            return this;
        }

        public void setEdit(String text) {
            if (null == text) {
                text = "";
            }
            edit.setText(text);
        }

        public String getEditString() {
            return edit.getText().toString();
        }

        public void addTextChangedListener(TextWatcher watcher) {
            edit.addTextChangedListener(watcher);
        }

        /**
         * 设置 Warning
         */
        public Builder setWarning(String waring) {
            text_warning.setText(waring);
            if (waring == null || waring.equals("")) {
                text_warning.setVisibility(View.GONE);
            } else {
                text_warning.setVisibility(View.VISIBLE);
            }
            return this;
        }


        /**
         * 设置取消按钮文字和监听
         */
        public Builder setButtonCancel(String text, View.OnClickListener listener) {
            button_cancel.setText(text);
            mButtonCancelClickListener = listener;
            return this;
        }

        public Builder setButtonCancel(View.OnClickListener listener) {
            mButtonCancelClickListener = listener;
            return this;
        }

        /**
         * 设置确认按钮文字和监听
         */
        public Builder setButtonConfirm(String text, View.OnClickListener listener) {
            button_ok.setText(text);
            mButtonConfirmClickListener = listener;
            return this;
        }

        public Builder setButtonConfirm(View.OnClickListener listener) {
            mButtonConfirmClickListener = listener;
            return this;
        }

        public CustomDialog create() {
            button_cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mDialog.dismiss();
                    if (null != mButtonCancelClickListener) {
                        mButtonCancelClickListener.onClick(view);
                    }
                }
            });

            button_ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mDialog.dismiss();
                    if (null != mButtonConfirmClickListener) {
                        mButtonConfirmClickListener.onClick(view);
                    }
                }
            });

            mDialog.setContentView(mLayout);
            mDialog.setCancelable(true);
            mDialog.setCanceledOnTouchOutside(false);
            return mDialog;
        }
    }
}