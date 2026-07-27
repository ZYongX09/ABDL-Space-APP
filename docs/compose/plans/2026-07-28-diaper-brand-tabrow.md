# Diaper Brand TabRow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the diaper list's Java brand chips with the real Miuix `TabRowWithContour` while preserving the existing filtering and pagination behavior.

**Architecture:** Add a focused Kotlin bridge that owns a `ComposeView`, wraps `TabRowWithContour` in `MiuixAppTheme`, and exposes Java-callable brand/selection updates. `DiaperListFragment` remains the source of truth for brands and selected brand, and receives index callbacks from the bridge.

**Tech Stack:** Java Fragment/AppKit, Kotlin, Jetpack Compose, `miuix-ui-android`, existing `MiuixAppTheme`.

---

### Task 1: Add The Miuix Brand Tab Bridge

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/diapers/DiaperBrandTabRowView.kt`

- [ ] **Step 1: Create a Java-callable bridge with explicit state**

Implement a class that extends `FrameLayout`, internally hosts a `ComposeView`, and exposes:

```kotlin
class DiaperBrandTabRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var tabs by mutableStateOf(emptyList<String>())
    private var selectedIndex by mutableIntStateOf(0)
    private var onTabSelected: ((Int) -> Unit)? = null

    fun setTabs(tabs: List<String>, selectedIndex: Int) {
        this.tabs = tabs.toList()
        this.selectedIndex = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }

    fun setOnTabSelectedListener(listener: OnTabSelectedListener?) {
        onTabSelected = listener?.let { callback -> { index -> callback.onTabSelected(index) } }
    }

    fun interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }
}
```

Create the `ComposeView` in `init`, set `ViewCompositionStrategy.DisposeOnDetachedFromWindow`, and render:

```kotlin
MiuixAppTheme {
    if (tabs.isNotEmpty()) {
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedIndex,
            onTabSelected = { index ->
                if (index != selectedIndex) {
                    selectedIndex = index
                    onTabSelected?.invoke(index)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            listState = rememberLazyListState(),
        )
    }
}
```

- [ ] **Step 2: Compile the Kotlin bridge**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: `BUILD SUCCESSFUL` and Java-visible methods `setTabs` and `setOnTabSelectedListener` compile without synthetic Compose API leakage.

### Task 2: Replace Java Brand Chips

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperListFragment.java:15-20,63-65,156-167,258-264,362-389`

- [ ] **Step 1: Replace the chip container field**

Remove `HorizontalScrollView` and the `LinearLayout chipsContainer` field. Add:

```java
import org.joinmastodon.android.ui.compose.diapers.DiaperBrandTabRowView;

private DiaperBrandTabRowView brandTabRow;
```

- [ ] **Step 2: Create the TabRow bridge in the existing layout position**

Replace the Java chip construction block with:

```java
brandTabRow=new DiaperBrandTabRowView(getContext());
brandTabRow.setLayoutParams(new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
));
brandTabRow.setOnTabSelectedListener(index->{
    if(index<0 || index>=brands.size())
        return;
    String brand=brands.get(index);
    if(brand.equals(currentBrand))
        return;
    currentBrand=brand;
    currentPage=1;
    hasMore=true;
    updateBrandTabs();
    loadData();
});
root.addView(brandTabRow);
```

- [ ] **Step 3: Replace `buildChips()` with state synchronization**

Add:

```java
private void updateBrandTabs(){
    if(brandTabRow==null || brands.isEmpty())
        return;
    List<String> labels=new ArrayList<>(brands.size());
    for(String brand:brands)
        labels.add(brand.isEmpty() ? getString(R.string.diaper_all_brands) : brand);
    int selectedIndex=Math.max(0, brands.indexOf(currentBrand));
    brandTabRow.setTabs(labels, selectedIndex);
}
```

Change the brand API success callback from `buildChips()` to `updateBrandTabs()`.

- [ ] **Step 4: Verify no obsolete chip code remains**

Search:

```bash
rg "chipsContainer|buildChips|item_diaper_chip|HorizontalScrollView" mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperListFragment.java
```

Expected: no matches.

### Task 3: Build And Verify On Device

**Files:**
- Verify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/diapers/DiaperBrandTabRowView.kt`
- Verify: `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperListFragment.java`

- [ ] **Step 1: Build the debug APK**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Commit only the TabRow change after the successful build**

Run:

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/diapers/DiaperBrandTabRowView.kt \
  mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperListFragment.java \
  docs/compose/plans/2026-07-28-diaper-brand-tabrow.md
git commit -m "feat(diapers): 使用 Miuix 品牌标签栏"
```

- [ ] **Step 3: Install and verify interactions**

Install `mastodon/build/outputs/apk/debug/mastodon-debug.apk`, open the diaper list, and verify:

1. The brand selector uses the demo's contoured squircle TabRow.
2. “全部” is selected initially and restored after recreation.
3. Selecting a brand refreshes the list once and moves the indicator to that brand.
4. Long brand lists scroll horizontally and the selected tab auto-centers.
5. Search, pull-to-refresh, pagination, rankings navigation, light theme, and dark theme remain functional.

- [ ] **Step 4: Capture final evidence**

Take one light-theme and one dark-theme screenshot, and run:

```bash
git show --check --stat --oneline HEAD
git status --short
```

Expected: the commit contains only the bridge, fragment integration, and this plan; unrelated worktree changes remain untouched.
