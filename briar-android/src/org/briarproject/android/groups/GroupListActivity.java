package org.briarproject.android.groups;

import static android.view.Gravity.CENTER;
import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.Menu.NONE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_MATCH;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_WRAP;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_WRAP_1;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.briarproject.R;
import org.briarproject.android.BriarActivity;
import org.briarproject.android.util.HorizontalBorder;
import org.briarproject.android.util.LayoutUtils;
import org.briarproject.android.util.ListLoadingProgressBar;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.MessageHeader;
import org.briarproject.api.db.NoSuchSubscriptionException;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.MessageAddedEvent;
import org.briarproject.api.event.MessageExpiredEvent;
import org.briarproject.api.event.RemoteSubscriptionsUpdatedEvent;
import org.briarproject.api.event.SubscriptionAddedEvent;
import org.briarproject.api.event.SubscriptionRemovedEvent;
import org.briarproject.api.messaging.Group;
import org.briarproject.api.messaging.GroupId;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class GroupListActivity extends BriarActivity
implements EventListener, OnClickListener, OnItemClickListener,
OnCreateContextMenuListener {

	private static final int MENU_ITEM_UNSUBSCRIBE = 1;
	private static final Logger LOG =
			Logger.getLogger(GroupListActivity.class.getName());

	private final Map<GroupId,GroupId> groups =
			new ConcurrentHashMap<GroupId,GroupId>();

	private TextView empty = null;
	private GroupListAdapter adapter = null;
	private ListView list = null;
	private ListLoadingProgressBar loading = null;
	private TextView available = null;
	private ImageButton newGroupButton = null;

	// Fields that are accessed from background threads must be volatile
	@Inject private volatile DatabaseComponent db;
	@Inject private volatile EventBus eventBus;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(MATCH_MATCH);
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);

		int pad = LayoutUtils.getPadding(this);

		empty = new TextView(this);
		empty.setLayoutParams(MATCH_WRAP_1);
		empty.setGravity(CENTER);
		empty.setTextSize(18);
		empty.setText(R.string.no_forums);
		empty.setVisibility(GONE);
		layout.addView(empty);

		adapter = new GroupListAdapter(this);
		list = new ListView(this);
		list.setLayoutParams(MATCH_WRAP_1);
		list.setAdapter(adapter);
		list.setOnItemClickListener(this);
		list.setOnCreateContextMenuListener(this);
		list.setVisibility(GONE);
		layout.addView(list);

		// Show a progress bar while the list is loading
		loading = new ListLoadingProgressBar(this);
		layout.addView(loading);

		available = new TextView(this);
		available.setLayoutParams(MATCH_WRAP);
		available.setGravity(CENTER);
		available.setTextSize(18);
		available.setPadding(pad, pad, pad, pad);
		Resources res = getResources();
		int background = res.getColor(R.color.forums_available_background);
		available.setBackgroundColor(background);
		available.setOnClickListener(this);
		available.setVisibility(GONE);
		layout.addView(available);

		layout.addView(new HorizontalBorder(this));

		LinearLayout footer = new LinearLayout(this);
		footer.setLayoutParams(MATCH_WRAP);
		footer.setOrientation(HORIZONTAL);
		footer.setGravity(CENTER);
		footer.setBackgroundColor(res.getColor(R.color.button_bar_background));
		newGroupButton = new ImageButton(this);
		newGroupButton.setBackgroundResource(0);
		newGroupButton.setImageResource(R.drawable.social_new_chat);
		newGroupButton.setOnClickListener(this);
		footer.addView(newGroupButton);
		layout.addView(footer);

		setContentView(layout);
	}

	@Override
	public void onResume() {
		super.onResume();
		eventBus.addListener(this);
		loadHeaders();
	}

	private void loadHeaders() {
		clearHeaders();
		runOnDbThread(new Runnable() {
			public void run() {
				try {
					long now = System.currentTimeMillis();
					for(Group g : db.getGroups()) {
						try {
							displayHeaders(g, db.getMessageHeaders(g.getId()));
						} catch(NoSuchSubscriptionException e) {
							// Continue
						}
					}
					int available = db.getAvailableGroups().size();
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Full load took " + duration + " ms");
					displayAvailable(available);
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void clearHeaders() {
		runOnUiThread(new Runnable() {
			public void run() {
				groups.clear();
				empty.setVisibility(GONE);
				list.setVisibility(GONE);
				available.setVisibility(GONE);
				loading.setVisibility(VISIBLE);
				adapter.clear();
				adapter.notifyDataSetChanged();
			}
		});
	}

	private void displayHeaders(final Group g,
			final Collection<MessageHeader> headers) {
		runOnUiThread(new Runnable() {
			public void run() {
				GroupId id = g.getId();
				groups.put(id, id);
				list.setVisibility(VISIBLE);
				loading.setVisibility(GONE);
				// Remove the old item, if any
				GroupListItem item = findGroup(id);
				if(item != null) adapter.remove(item);
				// Add a new item
				adapter.add(new GroupListItem(g, headers));
				adapter.sort(GroupListItemComparator.INSTANCE);
				adapter.notifyDataSetChanged();
				selectFirstUnread();
			}
		});
	}

	private void displayAvailable(final int availableCount) {
		runOnUiThread(new Runnable() {
			public void run() {
				if(adapter.isEmpty()) empty.setVisibility(VISIBLE);
				loading.setVisibility(GONE);
				if(availableCount == 0) {
					available.setVisibility(GONE);
				} else {
					available.setVisibility(VISIBLE);
					available.setText(getResources().getQuantityString(
							R.plurals.forums_shared, availableCount,
							availableCount));
				}
			}
		});
	}

	private GroupListItem findGroup(GroupId g) {
		int count = adapter.getCount();
		for(int i = 0; i < count; i++) {
			GroupListItem item = adapter.getItem(i);
			if(item.getGroup().getId().equals(g)) return item;
		}
		return null; // Not found
	}

	private void selectFirstUnread() {
		int firstUnread = -1, count = adapter.getCount();
		for(int i = 0; i < count; i++) {
			if(adapter.getItem(i).getUnreadCount() > 0) {
				firstUnread = i;
				break;
			}
		}
		if(firstUnread == -1) list.setSelection(count - 1);
		else list.setSelection(firstUnread);
	}

	@Override
	public void onPause() {
		super.onPause();
		eventBus.removeListener(this);
	}

	public void eventOccurred(Event e) {
		if(e instanceof MessageAddedEvent) {
			Group g = ((MessageAddedEvent) e).getGroup();
			if(groups.containsKey(g.getId())) {
				LOG.info("Message added, reloading");
				loadHeaders(g);
			}
		} else if(e instanceof MessageExpiredEvent) {
			LOG.info("Message expired, reloading");
			loadHeaders();
		} else if(e instanceof RemoteSubscriptionsUpdatedEvent) {
			LOG.info("Remote subscriptions changed, reloading");
			loadAvailable();
		} else if(e instanceof SubscriptionAddedEvent) {
			LOG.info("Group added, reloading");
			loadHeaders();
		} else if(e instanceof SubscriptionRemovedEvent) {
			Group g = ((SubscriptionRemovedEvent) e).getGroup();
			if(groups.containsKey(g.getId())) {
				LOG.info("Group removed, reloading");
				loadHeaders();
			}
		}
	}

	private void loadHeaders(final Group g) {
		runOnDbThread(new Runnable() {
			public void run() {
				try {
					long now = System.currentTimeMillis();
					Collection<MessageHeader> headers =
							db.getMessageHeaders(g.getId());
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Partial load took " + duration + " ms");
					displayHeaders(g, headers);
				} catch(NoSuchSubscriptionException e) {
					removeGroup(g.getId());
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void removeGroup(final GroupId g) {
		runOnUiThread(new Runnable() {
			public void run() {
				GroupListItem item = findGroup(g);
				if(item != null) {
					groups.remove(g);
					adapter.remove(item);
					adapter.notifyDataSetChanged();
					if(adapter.isEmpty()) {
						empty.setVisibility(VISIBLE);
						list.setVisibility(GONE);
					} else {
						selectFirstUnread();
					}
				}
			}
		});
	}

	private void loadAvailable() {
		runOnDbThread(new Runnable() {
			public void run() {
				try {
					long now = System.currentTimeMillis();
					int available = db.getAvailableGroups().size();
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Loading available took " + duration + " ms");
					displayAvailable(available);
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	public void onClick(View view) {
		if(view == available) {
			startActivity(new Intent(this, AvailableGroupsActivity.class));
		} else if(view == newGroupButton) {
			startActivity(new Intent(this, CreateGroupActivity.class));
		}
	}

	public void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {
		Intent i = new Intent(this, GroupActivity.class);
		Group g = adapter.getItem(position).getGroup();
		i.putExtra("briar.GROUP_ID", g.getId().getBytes());
		i.putExtra("briar.GROUP_NAME", g.getName());
		startActivity(i);
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view,
			ContextMenu.ContextMenuInfo info) {
		String delete = getString(R.string.unsubscribe);
		menu.add(NONE, MENU_ITEM_UNSUBSCRIBE, NONE, delete);
	}

	@Override
	public boolean onContextItemSelected(MenuItem menuItem) {
		if(menuItem.getItemId() == MENU_ITEM_UNSUBSCRIBE) {
			ContextMenuInfo info = menuItem.getMenuInfo();
			int position = ((AdapterContextMenuInfo) info).position;
			GroupListItem item = adapter.getItem(position);
			removeSubscription(item.getGroup());
			String unsubscribed = getString(R.string.unsubscribed_toast);
			Toast.makeText(this, unsubscribed, LENGTH_SHORT).show();
		}
		return true;
	}

	private void removeSubscription(final Group g) {
		runOnDbThread(new Runnable() {
			public void run() {
				try {
					long now = System.currentTimeMillis();
					db.removeGroup(g);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Removing group took " + duration + " ms");
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}
}