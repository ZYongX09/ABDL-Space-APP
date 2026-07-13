# Diaper Rating Page Implementation Plan

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/diaper-rating-page.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Android diaper rating page that submits 5 score dimensions and an optional review to `/api/ratings` from the diaper detail page.

**Architecture:** Follow the existing Java/AppKit fragment stack patterns. Add one focused `PostDiaperRating` request class and one `DiaperRatingFragment` that builds the page with Android Views, then wire `DiaperDetailFragment`'s write-rating button to open it.

**Tech Stack:** Java, Android Views, AppKit `FragmentStackActivity`, `MastodonAPIRequest`, backend `/api/ratings` endpoint.

---

## File Structure

- Create `mastodon/src/main/java/org/joinmastodon/android/api/requests/diapers/PostDiaperRating.java` for `POST /api/ratings` with the required backend JSON fields.
- Create `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperRatingFragment.java` for the standalone rating UI and submission flow.
- Modify `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperDetailFragment.java` to open the rating fragment and refresh after returning.
- Modify `mastodon/src/main/res/values/strings.xml` only if build requires reusable strings; otherwise keep page text inline like the existing diaper fragments.

### Task 1: Rating API Request

**Covers:** [S1]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/requests/diapers/PostDiaperRating.java`

- [ ] **Step 1: Add request class**

```java
package org.joinmastodon.android.api.requests.diapers;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class PostDiaperRating extends MastodonAPIRequest<Map<String, Object>>{
	public PostDiaperRating(int diaperId, int absorptionScore, int comfortScore, int thicknessScore, int appearanceScore, int valueScore, String review){
		super(HttpMethod.POST, "/ratings", new TypeToken<Map<String, Object>>(){});
		setRequestBody(new Body(diaperId, absorptionScore, comfortScore, thicknessScore, appearanceScore, valueScore, review));
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}

	private static class Body{
		public int diaper_id;
		public int absorption_score;
		public int comfort_score;
		public int thickness_score;
		public int appearance_score;
		public int value_score;
		public String review;

		public Body(int diaperId, int absorptionScore, int comfortScore, int thicknessScore, int appearanceScore, int valueScore, String review){
			diaper_id=diaperId;
			absorption_score=absorptionScore;
			comfort_score=comfortScore;
			thickness_score=thicknessScore;
			appearance_score=appearanceScore;
			value_score=valueScore;
			this.review=review;
		}
	}
}
```

- [ ] **Step 2: Compile request class**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon`

Expected: build either passes or reports errors unrelated to this new request class.

### Task 2: Standalone Rating Fragment

**Covers:** [S2]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperRatingFragment.java`

- [ ] **Step 1: Add fragment UI and submission logic**

The fragment reads `diaper_id`, `account`, `brand`, `model`, and `product_type` arguments. It shows the product heading, a summary card, five `SeekBar` rows, a 500-character `EditText`, and a bottom submit button.

- [ ] **Step 2: Validate before submit**

If the review exceeds 500 chars, show `Toast` text `使用感受不能超过500字` and do not call the API.

- [ ] **Step 3: Submit**

Call `new PostDiaperRating(...).exec(accountID)`. On success show `评分成功` and call `Nav.finish(this)`. On error show the backend toast via `ErrorResponse.showToast(getContext())`.

### Task 3: Detail Page Navigation

**Covers:** [S3]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperDetailFragment.java`

- [ ] **Step 1: Replace write-rating toast with navigation**

Create `DiaperRatingFragment`, pass `account`, `diaper_id`, `brand`, `model`, and `product_type` from `diaperData`, then call `Nav.go(getActivity(), fragment)`.

- [ ] **Step 2: Refresh detail when returning**

In `onShown()`, if data was already loaded, call `loadData()` to refresh reviews after the rating page is closed.

### Task 4: Verification And Commit

**Covers:** [S4]

**Files:**
- Verify all changed files.

- [ ] **Step 1: Build**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/api/requests/diapers/PostDiaperRating.java mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperRatingFragment.java mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperDetailFragment.java docs/compose/plans/2026-07-13-diaper-rating-page.md
git commit -m "feat: 添加纸尿裤评分页"
```

## Self-Review

- Spec coverage: S1 API request, S2 page UI/submission, S3 detail navigation, S4 verification covered.
- Placeholder scan: no TBD/TODO remains in the plan.
- Type consistency: backend fields match `ratings.ts` POST body: `diaper_id`, `absorption_score`, `comfort_score`, `thickness_score`, `appearance_score`, `value_score`, `review`.
