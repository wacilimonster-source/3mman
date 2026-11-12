package com.m3man.ui.basemain;


import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;
import androidx.core.widget.PopupWindowCompat;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseViewHolder;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.BuildConfig;
import com.m3man.R;
import com.m3man.adapter.BaseMainFragmentAdapter;
import com.m3man.adapter.SortCategoryAdapter;
import com.m3man.data.db.entity.Category;
import com.m3man.eventbus.LowMemoryEvent;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.download.DownloadActivity;
import com.m3man.ui.mman9video.source441447.Source441447Fragment;
import com.m3man.utils.AnimationUtils;
import com.m3man.utils.AppLog;
import com.m3man.utils.FragmentUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * A simple {@link Fragment} subclass.
 *
 * @author flymegoc
 */
public abstract class BaseMainFragment extends MvpFragment<BaseMainView, BaseMainPresenter> implements BaseMainView, View.OnClickListener, SortCategoryAdapter.OnStartDragListener {

    private static final String TAG = BaseMainFragment.class.getSimpleName();
    @BindView(R.id.tabLayout)
    TabLayout tabLayout;
    @BindView(R.id.iv_sort_category)
    AppCompatImageButton ivSortCategory;
    @BindView(R.id.viewPager)
    ViewPager viewPager;
    Unbinder unbinder;
    private PopupWindow popupWindow;
    private BaseMainFragmentAdapter mBaseMainFragmentAdapter;
    private List<Category> categoryList;
    private List<Category> sortCategoryList;
    private ItemTouchHelper mItemTouchHelper;
    private SortCategoryAdapter sortCategoryAdapter;
    private boolean isNeedInterruptOnBackPressed = false;
    private FragmentManager fragmentManager;
    private int currentSelectPosition = 0;
    private boolean isBackground = false;
    private static final String SOURCE_FRAGMENT_TAG = "source_441447";
    private static final String SOURCE_PREFS = "video_source_selection";
    private static final String PREF_SHOW_441447 = "show_441447";
    /** 来源切换控件与根视图：onResume / 初始状态用它们兜底同步来源显隐。 */
    private View sourceSelector;
    private TextView sourceSelectorLabel;
    private boolean show441447;
    private View sourceRootView;

    @Inject
    BaseMainPresenter baseMainPresenter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        EventBus.getDefault().register(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        fragmentManager = getChildFragmentManager();
        mBaseMainFragmentAdapter = new BaseMainFragmentAdapter(fragmentManager, categoryList, getCategoryType());
    }

    public BaseMainFragment() {
        // Required empty public constructor
        categoryList = new ArrayList<>();
        sortCategoryList = new ArrayList<>();
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_base_main, container, false);
    }


    @NonNull
    @Override
    public BaseMainPresenter createPresenter() {
        return baseMainPresenter;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        // EX-01：首页头部右侧下载入口，进入现有下载管理页；不放搜索入口。
        View downloadEntry = view.findViewById(R.id.iv_home_download);
        if (downloadEntry != null) {
            downloadEntry.setOnClickListener(v ->
                    startActivity(new Intent(context, DownloadActivity.class)));
        }
//        Drawable dropDownDrawable = ResourceUtil.tintList(context, R.drawable.ic_arrow_drop_down_black_24dp, R.color.common_always_white_text_color);
//        ivSortCategory.setImageDrawable(dropDownDrawable);
        ivSortCategory.setOnClickListener(this);
        viewPager.setAdapter(mBaseMainFragmentAdapter);
        tabLayout.setupWithViewPager(viewPager);
        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                currentSelectPosition = position;
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
        setupSourceSelector(view);
    }

    private void setupSourceSelector(View view) {
        sourceRootView = view;
        sourceSelector = view.findViewById(R.id.fl_source_selector);
        sourceSelectorLabel = view.findViewById(R.id.tv_source_selector);
        android.content.SharedPreferences preferences = requireContext()
                .getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE);
        show441447 = preferences.getBoolean(PREF_SHOW_441447, false);
        updateSourceSelector();
        // 首次进入显式应用一次，保证可见状态与记忆值一致。
        applySourceVisibility(show441447);
        sourceSelector.setOnClickListener(v -> showSourcePicker());
    }

    /** 同步头部胶囊文案与无障碍描述，始终显示完整来源名。 */
    private void updateSourceSelector() {
        if (sourceSelectorLabel == null || !isAdded()) {
            return;
        }
        int labelRes = show441447 ? R.string.home_source_441447 : R.string.home_source_main;
        sourceSelectorLabel.setText(labelRes);
        if (sourceSelector != null) {
            sourceSelector.setContentDescription(getString(R.string.home_source_selector_desc)
                    + "，" + getString(labelRes));
        }
    }

    /** 方案 D：底部来源面板；名称和说明都完整展示，选择后立即切换。 */
    private void showSourcePicker() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        final BottomSheetDialog dialog = new BottomSheetDialog(requireContext(), R.style.RecoFilterSheetTheme);
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_source_picker, null);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        final View mainRow = content.findViewById(R.id.row_source_main);
        final View sourceRow = content.findViewById(R.id.row_source_441447);
        final ImageView mainCheck = content.findViewById(R.id.iv_source_main_check);
        final ImageView sourceCheck = content.findViewById(R.id.iv_source_441447_check);

        final Runnable render = () -> {
            mainRow.setSelected(!show441447);
            sourceRow.setSelected(show441447);
            mainCheck.setVisibility(show441447 ? View.GONE : View.VISIBLE);
            sourceCheck.setVisibility(show441447 ? View.VISIBLE : View.GONE);
            mainRow.setContentDescription(getString(show441447
                            ? R.string.source_picker_option_desc
                            : R.string.source_picker_option_selected_desc,
                    getString(R.string.home_source_main), getString(R.string.source_picker_main_desc)));
            sourceRow.setContentDescription(getString(show441447
                            ? R.string.source_picker_option_selected_desc
                            : R.string.source_picker_option_desc,
                    getString(R.string.home_source_441447), getString(R.string.source_picker_441447_desc)));
        };
        render.run();

        mainRow.setOnClickListener(v -> {
            dialog.dismiss();
            selectSource(false);
        });
        sourceRow.setOnClickListener(v -> {
            dialog.dismiss();
            selectSource(true);
        });
        content.findViewById(R.id.iv_source_picker_close).setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) {
                return;
            }
            sheet.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            sheet.setLayoutParams(lp);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
            behavior.setHideable(true);
        });
        dialog.show();
    }

    private void selectSource(boolean showSource) {
        if (show441447 == showSource) {
            applySourceVisibility(showSource);
            return;
        }
        show441447 = showSource;
        if (isAdded()) {
            requireContext().getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_SHOW_441447, showSource).apply();
        }
        updateSourceSelector();
        applySourceVisibility(showSource);
    }

    /**
     * 按来源统一控制主站内容 / 441447 内容的显隐。
     * <p>
     * 两者放在同一个 FrameLayout 容器里且同一时刻只允许一个可见；
     * 切换时先隐藏对方再显示自己，避免任何回调遗漏时两层内容同时绘制造成遮挡。
     */
    private void applySourceVisibility(boolean showSource) {
        View root = getView() != null ? getView() : sourceRootView;
        if (root == null) {
            return;
        }
        if (showSource && popupWindow != null && popupWindow.isShowing()) {
            dismissPopupWindow();
        }
        View categoryBar = root.findViewById(R.id.main_category_bar);
        View categoryDivider = root.findViewById(R.id.main_category_divider);
        ViewPager pager = root.findViewById(R.id.viewPager);
        View container = root.findViewById(R.id.source_441447_container);
        if (showSource) {
            if (pager != null) pager.setVisibility(View.GONE);
            if (categoryBar != null) categoryBar.setVisibility(View.GONE);
            if (categoryDivider != null) categoryDivider.setVisibility(View.GONE);
            if (container != null) {
                container.setVisibility(View.VISIBLE);
                container.bringToFront();
            }
            ensureSourceFragmentAdded();
        } else {
            if (container != null) container.setVisibility(View.GONE);
            if (categoryBar != null) categoryBar.setVisibility(View.VISIBLE);
            if (categoryDivider != null) categoryDivider.setVisibility(View.VISIBLE);
            if (pager != null) {
                pager.setVisibility(View.VISIBLE);
                pager.bringToFront();
            }
        }
    }

    private void ensureSourceFragmentAdded() {
        if (getChildFragmentManager().findFragmentByTag(SOURCE_FRAGMENT_TAG) == null) {
            getChildFragmentManager().beginTransaction()
                    .add(R.id.source_441447_container, new Source441447Fragment(), SOURCE_FRAGMENT_TAG)
                    .commitNowAllowingStateLoss();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        isBackground = false;
        // 视图重建、进程恢复或从播放页返回后，按当前记忆的来源兜底同步一次，
        // 防止来源容器与主站内容出现上下叠放。
        if (sourceSelector != null) {
            applySourceVisibility(show441447);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        isBackground = true;
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        presenter.loadCategoryData(getCategoryType());
    }

    public abstract int getCategoryType();

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (popupWindow != null) {
            // 先摘掉 OnDismissListener：销毁阶段不应再触发分类落库/刷新（此时 View 已解绑）
            popupWindow.setOnDismissListener(null);
            popupWindow.dismiss();
            popupWindow = null;
        }
        sourceSelector = null;
        sourceSelectorLabel = null;
        sourceRootView = null;
        unbinder.unbind();
    }

    private void showSortCategoryPopupWindow() {
        if (sortCategoryList.size() == 0) {
            presenter.loadAllCategoryData(getCategoryType());
        }
        if (popupWindow == null) {
            View view = getLayoutInflater().inflate(R.layout.layout_popuwindow_sort_category, tabLayout, false);
            RecyclerView sortCategoryRecyclerView = view.findViewById(R.id.recyclerView_sort_category);
            popupWindow = new PopupWindow(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                popupWindow.setElevation(10);
            }
            popupWindow.setOutsideTouchable(true);
            // 必须设置 focusable + 背景，否则外部点击无法关闭，且点击会穿透到下方控件
            popupWindow.setFocusable(true);
            popupWindow.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(context, android.R.color.white)));
            popupWindow.setOnDismissListener(new PopupWindow.OnDismissListener() {
                @Override
                public void onDismiss() {
                    AnimationUtils.rotateDown(ivSortCategory);
                    if (isNeedUpdate()) {
                        presenter.updateCategoryData(sortCategoryList);
                        presenter.loadCategoryData(getCategoryType());
                    }
                    isNeedInterruptOnBackPressed = false;
                }
            });
            popupWindow.setAnimationStyle(R.style.sort_category_popuwindow_anim);
            sortCategoryAdapter = new SortCategoryAdapter(R.layout.item_sort_category, sortCategoryList);
            sortCategoryAdapter.setOnStartDragListener(this);
            sortCategoryRecyclerView.setLayoutManager(new LinearLayoutManager(context));
            sortCategoryRecyclerView.setHasFixedSize(true);
            sortCategoryRecyclerView.addItemDecoration(new DividerItemDecoration(context, DividerItemDecoration.VERTICAL));
            sortCategoryRecyclerView.setAdapter(sortCategoryAdapter);
            initItemTouchHelper(sortCategoryAdapter, sortCategoryRecyclerView, sortCategoryList);
            // 注意：showAsDropDown 不能传 Gravity.BOTTOM，否则弹窗会向上覆盖工具栏并挡住排序按钮，
            // 导致第二次点击被弹窗吸收而无法关闭。使用默认（下拉在 anchor 之下）即可。
            PopupWindowCompat.showAsDropDown(popupWindow, tabLayout, 0, 0, Gravity.NO_GRAVITY);
            AnimationUtils.rotateUp(ivSortCategory);
            isNeedInterruptOnBackPressed = true;
            showMessage(getString(R.string.common_drag_slider_tip), TastyToast.INFO);
        } else {
            if (popupWindow.isShowing()) {
                dismissPopupWindow();
            } else {
                AnimationUtils.rotateUp(ivSortCategory);
                PopupWindowCompat.showAsDropDown(popupWindow, tabLayout, 0, 0, Gravity.NO_GRAVITY);
                isNeedInterruptOnBackPressed = true;
            }
        }
    }

    private void dismissPopupWindow() {
        if (popupWindow == null || !popupWindow.isShowing()) {
            return;
        }
        // 统一由 OnDismissListener 负责旋转动画、分类落库与刷新，避免重复执行
        popupWindow.dismiss();
        isNeedInterruptOnBackPressed = false;
    }

    private boolean isNeedUpdate() {
        boolean needUpdate = false;
        // H-09：批量加载分类到 Map，避免循环中 N+1 查询 DB
        Map<Long, Category> oldCategoryMap = presenter.loadCategoryMap(getCategoryType());
        for (Category category : sortCategoryList) {
            //先检查排序
            int newSortId = sortCategoryList.indexOf(category);
            if (newSortId != category.getSortId()) {
                category.setSortId(newSortId);
                needUpdate = true;
            }
            //在检查是否可见
            Category oldCategory = oldCategoryMap.get(category.getId());
            if (oldCategory != null && oldCategory.getIsShow() != category.getIsShow()) {
                needUpdate = true;
            }
        }
        return needUpdate;
    }

    public boolean onBackPressed() {
        boolean result = isNeedInterruptOnBackPressed;
        if (isNeedInterruptOnBackPressed) {
            dismissPopupWindow();
        }
        return result;
    }

    private void initItemTouchHelper(final SortCategoryAdapter sortCategoryAdapter, RecyclerView sortCategoryRecyclerView, final List<Category> categoryList) {
        //itemHelper的回调
        ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {

            @Override
            public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                //也就是说返回值是组合式的
                //makeMovementFlags (int dragFlags, int swipeFlags)，看下面的解释说明
                int swipFlag = 0;
                //如果也监控左右方向的话，swipFlag=ItemTouchHelper.LEFT|ItemTouchHelper.RIGHT;
                int dragflag = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                //等价于：0001&0010;多点触控标记触屏手指的顺序和个数也是这样标记哦
                return makeMovementFlags(dragflag, swipFlag);
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                //直接按照文档来操作啊，这文档写得太给力了,简直完美！
                sortCategoryAdapter.notifyItemMoved(viewHolder.getAdapterPosition(), target.getAdapterPosition());
                //注意这里有个坑的，itemView 都移动了，对应的数据也要移动
                Collections.swap(categoryList, viewHolder.getAdapterPosition(), target.getAdapterPosition());

                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                //暂不处理,处理左右滑动
            }

            @Override
            public boolean canDropOver(RecyclerView recyclerView, RecyclerView.ViewHolder current, RecyclerView.ViewHolder target) {
                return true;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                //return true后，可以实现长按拖动排序和拖动动画了
                return false;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                    viewHolder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.sort_category_drag_color));
                }
                super.onSelectedChanged(viewHolder, actionState);
            }

            @Override
            public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setBackgroundColor(0);
            }
        };

        //1.创建item helper
        mItemTouchHelper = new ItemTouchHelper(callback);
        //2.绑定到recyclerview上面去
        mItemTouchHelper.attachToRecyclerView(sortCategoryRecyclerView);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.iv_sort_category:
                showSortCategoryPopupWindow();
                break;
            default:
        }
    }

    @Override
    public void showLoading(boolean pullToRefresh) {

    }

    @Override
    public void showContent() {

    }

    // BaseView 接口要求 public；父类实现为 protected，这里必须提升可见性（勿删）
    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {

    }

    @Override
    public void onLoadCategoryData(List<Category> categoryList) {
        this.categoryList.clear();
        this.categoryList.addAll(categoryList);
        mBaseMainFragmentAdapter.notifyDataSetChanged();
    }

    @Override
    public void onLoadAllCategoryData(List<Category> categoryList) {
        sortCategoryList.clear();
        sortCategoryList.addAll(categoryList);
        if (sortCategoryAdapter != null) {
            sortCategoryAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void startDragItem(BaseViewHolder helper) {
        mItemTouchHelper.startDrag(helper);
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
        Logger.t(TAG).d("------------------onDestroy()");
    }

    /**
     * 尝试释放内存，因为我们使用的是FragmentPagerAdapter，导致即使页面不可见也不会释放内存，好处在于返回该页面时所有状态视图会保持为之前的状态
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTryToReleaseMemory(LowMemoryEvent lowMemoryEvent) {
        //有可能已经被回收或者还没创建（概率）
        if (!isBackground || categoryList == null || fragmentManager == null || viewPager == null || mBaseMainFragmentAdapter == null) {
            return;
        }
        if (!BuildConfig.DEBUG) {
            //Bugsnag.notify(new Throwable(TAG + ":LowMemory,try to release some memory now!"), Severity.INFO);
        }
        try {
            Logger.t(TAG).d("start try to release memory ....");
            FragmentTransaction mCurTransaction = fragmentManager.beginTransaction();

            //获取当前不可见的fragment，并尝试释放掉它们来释放内存，但同时会导致切换回该页面时所有状态均没有了，会被重新创建
            for (int i = 0; i < categoryList.size(); i++) {
                //尝试移除不可见的fragment
                if (i < currentSelectPosition - 1 || i > currentSelectPosition + 1) {
                    long itemId = mBaseMainFragmentAdapter.getItemId(i);
                    String name = FragmentUtils.makeFragmentName(viewPager.getId(), itemId);
                    Fragment fragment = fragmentManager.findFragmentByTag(name);
                    if (fragment != null) {
                        mCurTransaction.remove(fragment);
                    }
                }
            }
            mCurTransaction.commitNowAllowingStateLoss();
            Logger.t(TAG).d("try to release memory success !!!");
        } catch (Exception e) {
            AppLog.e(TAG, "tryToReleaseMemory error: " + AppLog.cause(e));
        }
    }
}
