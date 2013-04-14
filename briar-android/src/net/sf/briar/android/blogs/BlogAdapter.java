package net.sf.briar.android.blogs;

import static android.graphics.Typeface.BOLD;
import static android.view.Gravity.CENTER_VERTICAL;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;
import static java.text.DateFormat.SHORT;
import static net.sf.briar.android.widgets.CommonLayoutParams.WRAP_WRAP_1;
import static net.sf.briar.api.messaging.Rating.BAD;
import static net.sf.briar.api.messaging.Rating.GOOD;

import java.util.ArrayList;

import net.sf.briar.R;
import net.sf.briar.android.widgets.HorizontalSpace;
import net.sf.briar.api.Author;
import net.sf.briar.api.db.GroupMessageHeader;
import net.sf.briar.api.messaging.Rating;
import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

class BlogAdapter extends ArrayAdapter<GroupMessageHeader> {

	BlogAdapter(Context ctx) {
		super(ctx, android.R.layout.simple_expandable_list_item_1,
				new ArrayList<GroupMessageHeader>());
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		GroupMessageHeader item = getItem(position);
		Context ctx = getContext();
		Resources res = ctx.getResources();

		LinearLayout layout = new LinearLayout(ctx);
		layout.setOrientation(HORIZONTAL);
		if(!item.isRead())
			layout.setBackgroundColor(res.getColor(R.color.unread_background));

		LinearLayout innerLayout = new LinearLayout(ctx);
		// Give me all the unused width
		innerLayout.setLayoutParams(WRAP_WRAP_1);
		innerLayout.setOrientation(VERTICAL);

		LinearLayout authorLayout = new LinearLayout(ctx);
		authorLayout.setOrientation(HORIZONTAL);
		authorLayout.setGravity(CENTER_VERTICAL);

		ImageView thumb = new ImageView(ctx);
		thumb.setPadding(10, 10, 10, 10);
		Rating rating = item.getRating();
		if(rating == GOOD) thumb.setImageResource(R.drawable.rating_good);
		else if(rating == BAD) thumb.setImageResource(R.drawable.rating_bad);
		else thumb.setImageResource(R.drawable.rating_unrated);
		authorLayout.addView(thumb);

		TextView name = new TextView(ctx);
		// Give me all the unused width
		name.setLayoutParams(WRAP_WRAP_1);
		name.setTextSize(18);
		name.setMaxLines(1);
		name.setPadding(0, 10, 10, 10);
		Author author = item.getAuthor();
		if(author == null) {
			name.setTextColor(res.getColor(R.color.anonymous_author));
			name.setText(R.string.anonymous);
		} else {
			name.setText(author.getName());
		}
		authorLayout.addView(name);
		innerLayout.addView(authorLayout);

		if(item.getContentType().equals("text/plain")) {
			TextView subject = new TextView(ctx);
			subject.setTextSize(14);
			subject.setMaxLines(2);
			subject.setPadding(10, 0, 10, 10);
			if(!item.isRead()) subject.setTypeface(null, BOLD);
			String s = item.getSubject();
			subject.setText(s == null ? "" : s);
			innerLayout.addView(subject);
		} else {
			LinearLayout attachmentLayout = new LinearLayout(ctx);
			attachmentLayout.setOrientation(HORIZONTAL);
			ImageView attachment = new ImageView(ctx);
			attachment.setPadding(10, 0, 10, 10);
			attachment.setImageResource(R.drawable.content_attachment);
			attachmentLayout.addView(attachment);
			attachmentLayout.addView(new HorizontalSpace(ctx));
			innerLayout.addView(attachmentLayout);
		}
		layout.addView(innerLayout);

		TextView date = new TextView(ctx);
		date.setTextSize(14);
		date.setPadding(0, 10, 10, 10);
		long then = item.getTimestamp(), now = System.currentTimeMillis();
		date.setText(DateUtils.formatSameDayTime(then, now, SHORT, SHORT));
		layout.addView(date);

		return layout;
	}
}
