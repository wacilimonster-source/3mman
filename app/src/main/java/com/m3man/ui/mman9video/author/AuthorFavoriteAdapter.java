package com.m3man.ui.mman9video.author;

import android.text.TextUtils;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.m3man.R;
import com.m3man.data.db.entity.AuthorFavorite;

/**
 * 作者收藏列表适配器。
 */
public class AuthorFavoriteAdapter extends BaseQuickAdapter<AuthorFavorite, BaseViewHolder> {

    public AuthorFavoriteAdapter() {
        super(R.layout.item_author_favorite);
    }

    @Override
    protected void convert(BaseViewHolder helper, AuthorFavorite item) {
        // 注册左滑「删除」按钮的点击事件（否则 setOnItemChildClickListener 不生效）
        helper.addOnClickListener(R.id.right_menu_delete);
        TextView name = helper.getView(R.id.tv_author_name);
        TextView source = helper.getView(R.id.tv_author_source);
        name.setText(item.getAuthorName());
        String src = item.getSource();
        if (TextUtils.equals(src, AuthorFavorite.SOURCE_PORNY)) {
            source.setText("分分钟 (91porny)");
        } else {
            source.setText("视频源 (mman9)");
        }
    }
}
