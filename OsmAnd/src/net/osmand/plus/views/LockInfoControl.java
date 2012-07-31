package net.osmand.plus.views;

import net.londatiga.android.ActionItem;
import net.londatiga.android.QuickAction;
import net.osmand.access.AccessibleToast;
import net.osmand.plus.NavigationService;
import net.osmand.plus.R;
import net.osmand.plus.background.OsmandBackgroundServicePlugin;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

public class LockInfoControl {
	
	protected boolean isScreenLocked;
	private View transparentLockView;
	private Drawable lockEnabled;
	private Drawable lockDisabled;
	
	public ImageView createLockScreenWidget(final OsmandMapTileView view) {
		final ImageView lockView = new ImageView(view.getContext());
		lockEnabled = view.getResources().getDrawable(R.drawable.lock_enabled);
		lockDisabled = view.getResources().getDrawable(R.drawable.lock_disabled);
		updateLockIcon(view, lockView);
		lockView.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showBgServiceQAction(lockView, view);
			}
		});
		return lockView;
	}

	private void updateLockIcon(final OsmandMapTileView view, final ImageView lockView) {
		if (isScreenLocked) {
			lockView.setBackgroundDrawable(lockEnabled);
		} else {
			lockView.setBackgroundDrawable(lockDisabled);
		}
	}

	private void showBgServiceQAction(final ImageView lockView, final OsmandMapTileView view) {	
		final QuickAction bgAction = new QuickAction(lockView);
		
		if (transparentLockView == null) {
			transparentLockView = new FrameLayout(view.getContext());
			FrameLayout.LayoutParams fparams = new FrameLayout.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT,
					Gravity.CENTER);
			transparentLockView.setLayoutParams(fparams);
			transparentLockView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					if (event.getAction() == MotionEvent.ACTION_UP) {
						int[] locs = new int[2];
						lockView.getLocationOnScreen(locs);
						int x = (int) event.getX() - locs[0];
						int y = (int) event.getY() - locs[1];
						transparentLockView.getLocationOnScreen(locs);
						x += locs[0];
						y += locs[1];
						if(lockView.getWidth() >= x && x >= 0 && 
								lockView.getHeight() >= y && y >= 0) {
							showBgServiceQAction(lockView, view);
							return true;
						}
						blinkIcon();
						AccessibleToast.makeText(transparentLockView.getContext(), R.string.screen_is_locked, Toast.LENGTH_LONG)
								.show();
						return true;
					}
					return true;
				}

				private void blinkIcon() {
					lockView.setBackgroundDrawable(lockDisabled);
					view.postDelayed(new Runnable() {
						@Override
						public void run() {
							lockView.setBackgroundDrawable(lockEnabled);
						}
					}, 300);
				}

			});
		}
		final FrameLayout parent = (FrameLayout) view.getParent();
		final ActionItem lockScreenAction = new ActionItem();
		lockScreenAction.setTitle(view.getResources().getString(
				isScreenLocked ? R.string.bg_service_screen_unlock : R.string.bg_service_screen_lock));
		lockScreenAction.setIcon(view.getResources().getDrawable(isScreenLocked ? R.drawable.lock_enabled : R.drawable.lock_disabled));
		lockScreenAction.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (!isScreenLocked) {
					parent.addView(transparentLockView);
				} else {
					parent.removeView(transparentLockView);
				}
				isScreenLocked = !isScreenLocked;
				bgAction.dismiss();
				updateLockIcon(view, lockView);
			}
		});
		bgAction.addActionItem(lockScreenAction);
		
		final ActionItem bgServiceAction = new ActionItem();
		final boolean off = view.getApplication().getNavigationService() == null;
		bgServiceAction.setTitle(view.getResources().getString(off? R.string.bg_service_sleep_mode_on : R.string.bg_service_sleep_mode_off));
//		bgServiceAction.setIcon(view.getResources().getDrawable(R.drawable.car_small)); //TODO icon
		bgServiceAction.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent serviceIntent = new Intent(view.getContext(), NavigationService.class);
				if (view.getApplication().getNavigationService() == null) {
					startBgService(serviceIntent, view);
				} else {
					view.getContext().stopService(serviceIntent);
				}
				bgAction.dismiss();
			}
		});
		bgAction.addActionItem(bgServiceAction);
		bgAction.show();
		
	}
	
	public static class ValueHolder<T> {
		public T value;
	}
	
	private void showIntervalChooseDialog(final OsmandMapTileView view, final String patternMsg,
			String startText, final int[] seconds, final int[] minutes, final ValueHolder<Integer> v, OnClickListener onclick){
		final Context ctx = view.getContext();
		Builder dlg = new AlertDialog.Builder(view.getContext());
		dlg.setTitle(startText);
		LinearLayout ll = new LinearLayout(view.getContext());
		final TextView tv = new TextView(view.getContext());
		tv.setPadding(7, 3, 7, 0);
		tv.setText(String.format(patternMsg, ctx.getString(R.string.int_continuosly)));
		SeekBar sp = new SeekBar(view.getContext());
		sp.setPadding(7, 5, 7, 0);
		final int secondsLength = seconds.length;
    	final int minutesLength = minutes.length;
    	sp.setMax(secondsLength + minutesLength - 1);
		sp.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}
			
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				String s;
				if(progress == 0) {
					s = ctx.getString(R.string.int_continuosly);
				} else {
					if(progress < secondsLength) {
						s = seconds[progress] + ctx.getString(R.string.int_seconds);
						v.value = seconds[progress] * 1000;
					} else {
						s = minutes[progress - secondsLength] + ctx.getString(R.string.int_min);
						v.value = minutes[progress - secondsLength] * 60 * 1000;
					}
				}
				tv.setText(String.format(patternMsg, s));
				
			}
		});
		
    	for(int i=0; i<secondsLength +minutesLength - 1; i++) {
    		if(i < secondsLength) {
    			if(v.value <= seconds[i] * 1000) {
    				sp.setProgress(i);
    				break;
    			}
    		} else {
    			if(v.value <= minutes[i - secondsLength] * 1000 * 60) {
    				sp.setProgress(i);
    				break;
    			}	
    		}
    	}
		
		ll.setOrientation(LinearLayout.VERTICAL);
		ll.addView(tv);
		ll.addView(sp);
		dlg.setView(ll);
		dlg.setPositiveButton(R.string.default_buttons_ok, onclick);
		dlg.setNegativeButton(R.string.default_buttons_cancel, null);
		dlg.show();
	}
	private void startBgService(final Intent serviceIntent, final OsmandMapTileView view) {
		final ValueHolder<Integer> v = new ValueHolder<Integer>();
		v.value = view.getSettings().SERVICE_OFF_INTERVAL.get();
		showIntervalChooseDialog(view, view.getContext().getString(R.string.gps_wakeup_interval), 
				view.getContext().getString(R.string.bg_service_sleep_mode_on), OsmandBackgroundServicePlugin.SECONDS, OsmandBackgroundServicePlugin.MINUTES,
				v, new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				view.getSettings().SERVICE_OFF_INTERVAL.set(v.value);
				view.getContext().startService(serviceIntent);	
			}
		});
	}
}
