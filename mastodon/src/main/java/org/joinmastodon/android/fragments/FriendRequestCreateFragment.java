package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.friendrequests.CreateFriendRequest;
import org.joinmastodon.android.api.requests.friendrequests.UpdateFriendRequest;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.FriendRequestField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;

public class FriendRequestCreateFragment extends ToolbarFragment {
	private EditText titleInput, descriptionInput;
	private LinearLayout fieldsContainer;
	private String accountID;
	private String editRequestId;
	private String editTitle, editLookingFor, editDescription;
	private List<Map<String, Object>> editFields;
	private String selectedLookingFor = "";
	private TextView lookingForLabel;

	private static final String[] LOOKING_FOR_OPTIONS = {
		"弟弟", "妹妹", "哥哥", "姐姐", "爸爸", "妈妈",
		"朋友", "游戏搭子", "同城朋友", "对象", "基友", "闺蜜", "金主"
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountID = getArguments().getString("account");
		editRequestId = getArguments().getString("editRequestId");
		editTitle = getArguments().getString("editTitle");
		editLookingFor = getArguments().getString("editLookingFor");
		editDescription = getArguments().getString("editDescription");
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(editRequestId != null ? "编辑交友请求" : getString(R.string.friend_request_create));
	}

	@Override
	protected View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_friend_request_create, container, false);

		titleInput = view.findViewById(R.id.create_title);
		descriptionInput = view.findViewById(R.id.create_description);
		fieldsContainer = view.findViewById(R.id.create_fields);
		lookingForLabel = view.findViewById(R.id.create_looking_for_label);

		// 设置"找的类型"标签
		LinearLayout lookingForContainer = view.findViewById(R.id.create_looking_for_container);
		for (String option : LOOKING_FOR_OPTIONS) {
			TextView tag = new TextView(getContext());
			tag.setText(option);
			tag.setTextSize(13);
			tag.setPadding(24, 12, 24, 12);
			tag.setBackgroundResource(R.drawable.bg_tag);
			tag.setClickable(true);
			tag.setFocusable(true);

			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			);
			params.setMargins(0, 0, 16, 0);
			tag.setLayoutParams(params);

			tag.setOnClickListener(v -> {
				selectedLookingFor = option;
				lookingForLabel.setText("找：" + option);
				// 更新所有标签样式
				for (int i = 0; i < lookingForContainer.getChildCount(); i++) {
					View child = lookingForContainer.getChildAt(i);
					if (child instanceof TextView) {
						TextView tv = (TextView) child;
						if (tv.getText().toString().equals(option)) {
							tv.setBackgroundResource(R.drawable.bg_tag_selected);
							tv.setTextColor(0xFFFFFFFF);
						} else {
							tv.setBackgroundResource(R.drawable.bg_tag);
							tv.setTextColor(0xFF333333);
						}
					}
				}
			});

			lookingForContainer.addView(tag);
		}

		// 添加字段按钮
		view.findViewById(R.id.add_field_btn).setOnClickListener(v -> addFieldRow(null, null, false));

		// 保存按钮
		view.findViewById(R.id.save_btn).setOnClickListener(v -> save());

		// 编辑模式：填充数据
		if (editRequestId != null) {
			if (editTitle != null) titleInput.setText(editTitle);
			if (editDescription != null) descriptionInput.setText(editDescription);
			if (editLookingFor != null) {
				selectedLookingFor = editLookingFor;
				lookingForLabel.setText("找：" + editLookingFor);
			}
		}

		return view;
	}

	private void addFieldRow(String key, String value, boolean isPrimary) {
		View row = LayoutInflater.from(getContext()).inflate(R.layout.item_friend_request_field, fieldsContainer, false);
		EditText keyInput = row.findViewById(R.id.field_key_input);
		EditText valueInput = row.findViewById(R.id.field_value_input);
		Switch primarySwitch = row.findViewById(R.id.field_primary_switch);
		View removeBtn = row.findViewById(R.id.field_remove_btn);

		if (key != null) keyInput.setText(key);
		if (value != null) valueInput.setText(value);
		primarySwitch.setChecked(isPrimary);

		removeBtn.setOnClickListener(v -> fieldsContainer.removeView(row));

		fieldsContainer.addView(row);
	}

	private void save() {
		String title = titleInput.getText().toString().trim();
		String description = descriptionInput.getText().toString().trim();

		if (title.isEmpty()) {
			Toast.makeText(getContext(), "标题不能为空", Toast.LENGTH_SHORT).show();
			return;
		}
		if (selectedLookingFor.isEmpty()) {
			Toast.makeText(getContext(), "请选择找的类型", Toast.LENGTH_SHORT).show();
			return;
		}

		// 收集字段
		List<Map<String, Object>> fields = new ArrayList<>();
		for (int i = 0; i < fieldsContainer.getChildCount(); i++) {
			View row = fieldsContainer.getChildAt(i);
			EditText keyInput = row.findViewById(R.id.field_key_input);
			EditText valueInput = row.findViewById(R.id.field_value_input);
			Switch primarySwitch = row.findViewById(R.id.field_primary_switch);

			String key = keyInput.getText().toString().trim();
			String value = valueInput.getText().toString().trim();
			if (!key.isEmpty() && !value.isEmpty()) {
				Map<String, Object> field = new HashMap<>();
				field.put("field_key", key);
				field.put("field_value", value);
				field.put("is_primary", primarySwitch.isChecked() ? 1 : 0);
				fields.add(field);
			}
		}

		if (editRequestId != null) {
			// 编辑模式
			new UpdateFriendRequest(editRequestId, title, selectedLookingFor, description, fields)
				.setCallback(new Callback<Map<String, Object>>() {
					@Override
					public void onSuccess(Map<String, Object> result) {
						Toast.makeText(getContext(), "更新成功", Toast.LENGTH_SHORT).show();
						getActivity().onBackPressed();
					}

					@Override
					public void onError(ErrorResponse error) {
						error.showToast(getContext());
					}
				})
				.exec(accountID);
		} else {
			// 创建模式
			new CreateFriendRequest(title, selectedLookingFor, description, fields)
				.setCallback(new Callback<Map<String, Object>>() {
					@Override
					public void onSuccess(Map<String, Object> result) {
						Toast.makeText(getContext(), "发布成功", Toast.LENGTH_SHORT).show();
						getActivity().onBackPressed();
					}

					@Override
					public void onError(ErrorResponse error) {
						error.showToast(getContext());
					}
				})
				.exec(accountID);
		}
	}
}
