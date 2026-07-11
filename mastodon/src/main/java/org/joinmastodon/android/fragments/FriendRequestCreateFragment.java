package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
	private String[] editFieldKeys, editFieldValues;
	private String selectedLookingFor = "";
	private TextView lookingForLabel;

	// 交友类型（优先放前面）
	private static final String[] LOOKING_FOR_OPTIONS = {
		"找家长", "找朋友", "找弟弟", "找姐姐", "找哥哥", "找同城朋友", "找对象", "找妈妈",
		"找妹妹", "找游戏搭子", "找基友", "找闺蜜", "找金主"
	};

	// 字段名选项（常用在前）
	private static final String[] FIELD_NAME_OPTIONS = {
		"生理性别", "心理性别", "年龄", "生日", "城市", "QQ", "微信", "手机号",
		"X(原推特)", "Telegram", "博客", "宝宝新天地", "爱好", "出生地", "工作地", "现居地", "性取向", "会玩游戏"
	};

	// 默认字段
	private static final String[] DEFAULT_FIELDS = {"生理性别", "年龄", "城市"};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountID = getArguments().getString("account");
		editRequestId = getArguments().getString("editRequestId");
		editTitle = getArguments().getString("editTitle");
		editLookingFor = getArguments().getString("editLookingFor");
		editDescription = getArguments().getString("editDescription");
		editFieldKeys = getArguments().getStringArray("editFieldKeys");
		editFieldValues = getArguments().getStringArray("editFieldValues");
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(editRequestId != null ? "编辑交友请求" : getString(R.string.friend_request_create));
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_friend_request_create, container, false);

		titleInput = view.findViewById(R.id.create_title);
		descriptionInput = view.findViewById(R.id.create_description);
		fieldsContainer = view.findViewById(R.id.create_fields);
		lookingForLabel = view.findViewById(R.id.create_looking_for_label);

		// 设置"交友类型"标签
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
				lookingForLabel.setText(option);
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
		view.findViewById(R.id.add_field_btn).setOnClickListener(v -> addFieldRow(null, null));

		// 保存按钮
		view.findViewById(R.id.save_btn).setOnClickListener(v -> save());

		// 编辑模式：填充数据
		if (editRequestId != null) {
			if (editTitle != null) titleInput.setText(editTitle);
			if (editDescription != null) descriptionInput.setText(editDescription);
			if (editLookingFor != null) {
				selectedLookingFor = editLookingFor;
				lookingForLabel.setText(editLookingFor);
			}
			// 添加已有字段
			if (editFieldKeys != null && editFieldValues != null) {
				for (int i = 0; i < editFieldKeys.length; i++) {
					addFieldRow(editFieldKeys[i], i < editFieldValues.length ? editFieldValues[i] : null);
				}
			}
		} else {
			// 创建模式：添加默认字段
			for (String fieldName : DEFAULT_FIELDS) {
				addFieldRow(fieldName, null);
			}
		}

		return view;
	}

	private void addFieldRow(String key, String value) {
		View row = LayoutInflater.from(getContext()).inflate(R.layout.item_friend_request_field, fieldsContainer, false);
		Spinner keySpinner = row.findViewById(R.id.field_key_spinner);
		EditText valueInput = row.findViewById(R.id.field_value_input);
		View removeBtn = row.findViewById(R.id.field_remove_btn);

		// 设置Spinner
		ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, FIELD_NAME_OPTIONS);
		spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		keySpinner.setAdapter(spinnerAdapter);

		// 设置默认值
		if (key != null) {
			for (int i = 0; i < FIELD_NAME_OPTIONS.length; i++) {
				if (FIELD_NAME_OPTIONS[i].equals(key)) {
					keySpinner.setSelection(i);
					break;
				}
			}
		}
		if (value != null) valueInput.setText(value);

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
			Spinner keySpinner = row.findViewById(R.id.field_key_spinner);
			EditText valueInput = row.findViewById(R.id.field_value_input);

			String key = (String) keySpinner.getSelectedItem();
			String value = valueInput.getText().toString().trim();
			if (key != null && !value.isEmpty()) {
				Map<String, Object> field = new HashMap<>();
				field.put("field_key", key);
				field.put("field_value", value);
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
