package net.osmand.plus.download;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.Build;
import android.os.StatFs;
import android.support.annotation.UiThread;
import android.view.View;
import android.widget.Toast;

import net.osmand.Collator;
import net.osmand.IndexConstants;
import net.osmand.OsmAndCollator;
import net.osmand.PlatformUtil;
import net.osmand.access.AccessibleToast;
import net.osmand.map.OsmandRegions;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings.OsmandPreference;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.WorldRegion;
import net.osmand.plus.base.BasicProgressAsyncTask;
import net.osmand.plus.download.DownloadFileHelper.DownloadFileShowWarning;
import net.osmand.plus.download.DownloadOsmandIndexesHelper.AssetIndexItem;
import net.osmand.plus.helpers.DatabaseHelper;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@SuppressLint("NewApi")
public class DownloadIndexesThread {
	private BaseDownloadActivity uiActivity = null;
	private IndexFileList indexFiles = null;
	private Map<IndexItem, List<DownloadEntry>> entriesToDownload = new ConcurrentHashMap<IndexItem, List<DownloadEntry>>();
	private Set<DownloadEntry> currentDownloads = new HashSet<DownloadEntry>();
	private final Context ctx;
	private OsmandApplication app;
	private final static Log LOG = PlatformUtil.getLog(DownloadIndexesThread.class);
	private DownloadFileHelper downloadFileHelper;
	private List<BasicProgressAsyncTask<?, ?, ?>> currentRunningTask = Collections.synchronizedList(new ArrayList<BasicProgressAsyncTask<?, ?, ?>>());
	private Map<String, String> indexFileNames = new LinkedHashMap<>();
	private Map<String, String> indexActivatedFileNames = new LinkedHashMap<>();
	private java.text.DateFormat dateFormat;
	private List<IndexItem> itemsToUpdate = new ArrayList<>();

	private Map<WorldRegion, Map<String, IndexItem>> resourcesByRegions = new HashMap<>();
	private List<IndexItem> voiceRecItems = new LinkedList<>();
	private List<IndexItem> voiceTTSItems = new LinkedList<>();

	private final ReentrantLock resourcesLock = new ReentrantLock();

	DatabaseHelper dbHelper;

	public DownloadIndexesThread(Context ctx) {
		this.ctx = ctx;
		app = (OsmandApplication) ctx.getApplicationContext();
		downloadFileHelper = new DownloadFileHelper(app);
		dateFormat = app.getResourceManager().getDateFormat();
		dbHelper = new DatabaseHelper(app);
	}

	public ReentrantLock getResourcesLock() {
		return resourcesLock;
	}

	public DatabaseHelper getDbHelper() {
		return dbHelper;
	}

	public void clear() {
		indexFiles = null;
	}

	public void setUiActivity(BaseDownloadActivity uiActivity) {
		this.uiActivity = uiActivity;
	}

	public List<DownloadEntry> flattenDownloadEntries() {
		List<DownloadEntry> res = new ArrayList<DownloadEntry>();
		for (List<DownloadEntry> ens : getEntriesToDownload().values()) {
			if (ens != null) {
				res.addAll(ens);
			}
		}
		return res;
	}

	public List<IndexItem> getCachedIndexFiles() {
		return indexFiles != null ? indexFiles.getIndexFiles() : null;
	}


	public IndexFileList getIndexFiles() {
		return indexFiles;
	}

	public Map<String, String> getIndexFileNames() {
		return indexFileNames;
	}

	public Map<String, String> getIndexActivatedFileNames() {
		return indexActivatedFileNames;
	}

	public void updateLoadedFiles() {
		Map<String, String> indexActivatedFileNames = app.getResourceManager().getIndexFileNames();
		DownloadIndexFragment.listWithAlternatives(dateFormat, app.getAppPath(""), IndexConstants.EXTRA_EXT,
				indexActivatedFileNames);
		Map<String, String> indexFileNames = app.getResourceManager().getIndexFileNames();
		DownloadIndexFragment.listWithAlternatives(dateFormat, app.getAppPath(""), IndexConstants.EXTRA_EXT,
				indexFileNames);
		app.getAppCustomization().updatedLoadedFiles(indexFileNames, indexActivatedFileNames);
		DownloadIndexFragment.listWithAlternatives(dateFormat, app.getAppPath(IndexConstants.TILES_INDEX_DIR),
				IndexConstants.SQLITE_EXT, indexFileNames);
		app.getResourceManager().getBackupIndexes(indexFileNames);
		this.indexFileNames = indexFileNames;
		this.indexActivatedFileNames = indexActivatedFileNames;
		//updateFilesToDownload();
	}

	public Map<String, String> getDownloadedIndexFileNames() {
		return indexFileNames;
	}

	public boolean isDownloadedFromInternet() {
		return indexFiles != null && indexFiles.isDownloadedFromInternet();
	}

	public List<IndexItem> getItemsToUpdate() {
		return itemsToUpdate;
	}

	public Map<WorldRegion, Map<String, IndexItem>> getResourcesByRegions() {
		return resourcesByRegions;
	}

	public List<IndexItem> getVoiceRecItems() {
		return voiceRecItems;
	}

	public List<IndexItem> getVoiceTTSItems() {
		return voiceTTSItems;
	}

	private boolean prepareData(List<IndexItem> resources) {
		resourcesLock.lock();
		try {

			List<IndexItem> resourcesInRepository;
			if (resources != null) {
				resourcesInRepository = resources;
			} else {
				resourcesInRepository = DownloadActivity.downloadListIndexThread.getCachedIndexFiles();
			}
			if (resourcesInRepository == null) {
				return false;
			}

			resourcesByRegions.clear();
			voiceRecItems.clear();
			voiceTTSItems.clear();

			for (WorldRegion region : app.getWorldRegion().getFlattenedSubregions()) {
				processRegion(resourcesInRepository, false, region);
			}
			processRegion(resourcesInRepository, true, app.getWorldRegion());

			final Collator collator = OsmAndCollator.primaryCollator();
			final OsmandRegions osmandRegions = app.getRegions();

			Collections.sort(voiceRecItems, new Comparator<IndexItem>() {
				@Override
				public int compare(IndexItem lhs, IndexItem rhs) {
					return collator.compare(lhs.getVisibleName(app.getApplicationContext(), osmandRegions),
							rhs.getVisibleName(app.getApplicationContext(), osmandRegions));
				}
			});

			Collections.sort(voiceTTSItems, new Comparator<IndexItem>() {
				@Override
				public int compare(IndexItem lhs, IndexItem rhs) {
					return collator.compare(lhs.getVisibleName(app.getApplicationContext(), osmandRegions),
							rhs.getVisibleName(app.getApplicationContext(), osmandRegions));
				}
			});

			return true;

		} finally {
			resourcesLock.unlock();
		}
	}

	private void processRegion(List<IndexItem> resourcesInRepository, boolean processVoiceFiles, WorldRegion region) {
		String downloadsIdPrefix = region.getDownloadsIdPrefix();

		Map<String, IndexItem> regionResources = new HashMap<>();

		Set<DownloadActivityType> typesSet = new TreeSet<>(new Comparator<DownloadActivityType>() {
			@Override
			public int compare(DownloadActivityType dat1, DownloadActivityType dat2) {
				return dat1.getTag().compareTo(dat2.getTag());
			}
		});

		for (IndexItem resource : resourcesInRepository) {

			if (processVoiceFiles) {
				if (resource.getSimplifiedFileName().endsWith(".voice.zip")) {
					voiceRecItems.add(resource);
					continue;
				} else if (resource.getSimplifiedFileName().contains(".ttsvoice.zip")) {
					voiceTTSItems.add(resource);
					continue;
				}
			}

			if (!resource.getSimplifiedFileName().startsWith(downloadsIdPrefix)) {
				continue;
			}

			if (resource.type == DownloadActivityType.NORMAL_FILE
					|| resource.type == DownloadActivityType.ROADS_FILE) {
				if (resource.isAlreadyDownloaded(indexFileNames)) {
					region.processNewMapState(checkIfItemOutdated(resource)
							? WorldRegion.MapState.OUTDATED : WorldRegion.MapState.DOWNLOADED);
				} else {
					region.processNewMapState(WorldRegion.MapState.NOT_DOWNLOADED);
				}
			}
			typesSet.add(resource.getType());
			regionResources.put(resource.getSimplifiedFileName(), resource);
		}

		if (region.getSuperregion() != null && region.getSuperregion().getSuperregion() != app.getWorldRegion()) {
			if (region.getSuperregion().getResourceTypes() == null) {
				region.getSuperregion().setResourceTypes(typesSet);
			} else {
				region.getSuperregion().getResourceTypes().addAll(typesSet);
			}
		}

		region.setResourceTypes(typesSet);
		resourcesByRegions.put(region, regionResources);
	}

	public class DownloadIndexesAsyncTask extends BasicProgressAsyncTask<IndexItem, Object, String> implements DownloadFileShowWarning {

		private OsmandPreference<Integer> downloads;

		public DownloadIndexesAsyncTask(Context ctx) {
			super(ctx);
			downloads = app.getSettings().NUMBER_OF_FREE_DOWNLOADS;
		}

		@Override
		public void setInterrupted(boolean interrupted) {
			super.setInterrupted(interrupted);
			if (interrupted) {
				downloadFileHelper.setInterruptDownloading(true);
			}
		}

		@Override
		protected void onProgressUpdate(Object... values) {
			for (Object o : values) {
				if (o instanceof DownloadEntry) {
					if (uiActivity != null) {
						uiActivity.downloadListUpdated();
						uiActivity.updateFragments();
						DownloadEntry item = (DownloadEntry) o;
						String name = item.item.getBasename();
						long count = dbHelper.getCount(name, DatabaseHelper.DOWNLOAD_ENTRY) + 1;
						DatabaseHelper.HistoryDownloadEntry entry = new DatabaseHelper.HistoryDownloadEntry(name, count);
						if (count == 1) {
							dbHelper.add(entry, DatabaseHelper.DOWNLOAD_ENTRY);
						} else {
							dbHelper.update(entry, DatabaseHelper.DOWNLOAD_ENTRY);
						}
					}
				} else if (o instanceof IndexItem) {
					entriesToDownload.remove(o);
					if (uiActivity != null) {
						uiActivity.downloadListUpdated();
						uiActivity.updateFragments();
						IndexItem item = (IndexItem) o;

						long count = dbHelper.getCount(item.getBasename(), DatabaseHelper.DOWNLOAD_ENTRY) + 1;
						dbHelper.add(new DatabaseHelper.HistoryDownloadEntry(item.getBasename(), count), DatabaseHelper.DOWNLOAD_ENTRY);
					}
				} else if (o instanceof String) {
					String message = (String) o;
					if (!message.equals("I/O error occurred : Interrupted")) {
						if (uiActivity == null ||
								!message.equals(uiActivity.getString(R.string.shared_string_download_successful))) {
							AccessibleToast.makeText(ctx, message, Toast.LENGTH_LONG).show();
						}
					}
				}
			}
			super.onProgressUpdate(values);
		}

		@Override
		protected void onPreExecute() {
			currentRunningTask.add(this);
			super.onPreExecute();
			if (uiActivity != null) {
				downloadFileHelper.setInterruptDownloading(false);
				View mainView = uiActivity.findViewById(R.id.MainLayout);
				if (mainView != null) {
					mainView.setKeepScreenOn(true);
				}
				startTask(ctx.getString(R.string.shared_string_downloading) + ctx.getString(R.string.shared_string_ellipsis), -1);
			}
		}

		@Override
		protected void onPostExecute(String result) {
			if (result != null && result.length() > 0) {
				AccessibleToast.makeText(ctx, result, Toast.LENGTH_LONG).show();
			}
			currentDownloads.clear();
			if (uiActivity != null) {
				View mainView = uiActivity.findViewById(R.id.MainLayout);
				if (mainView != null) {
					mainView.setKeepScreenOn(false);
				}
				uiActivity.downloadedIndexes();
			}
			currentRunningTask.remove(this);
			if (uiActivity != null) {
				uiActivity.updateProgress(false, tag);
			}
			updateFilesToUpdate();
		}


		@Override
		protected String doInBackground(IndexItem... filesToDownload) {
			try {
				List<File> filesToReindex = new ArrayList<File>();
				boolean forceWifi = downloadFileHelper.isWifiConnected();
				currentDownloads = new HashSet<DownloadEntry>();
				String breakDownloadMessage = null;
				downloadCycle:
				while (!entriesToDownload.isEmpty()) {
					Iterator<Entry<IndexItem, List<DownloadEntry>>> it = entriesToDownload.entrySet().iterator();
					IndexItem file = null;
					List<DownloadEntry> list = null;
					while (it.hasNext()) {
						Entry<IndexItem, List<DownloadEntry>> n = it.next();
						if (!currentDownloads.containsAll(n.getValue())) {
							file = n.getKey();
							list = n.getValue();
							break;
						}
					}
					if (file == null) {
						break downloadCycle;
					}
					if (list != null) {
						boolean success = false;
						for (DownloadEntry entry : list) {
							if (currentDownloads.contains(entry)) {
								continue;
							}
							currentDownloads.add(entry);
							double asz = getAvailableSpace();
							// validate interrupted
							if (downloadFileHelper.isInterruptDownloading()) {
								break downloadCycle;
							}
							// validate enough space
							if (asz != -1 && entry.sizeMB > asz) {
								breakDownloadMessage = app.getString(R.string.download_files_not_enough_space, entry.sizeMB, asz);
								break downloadCycle;
							}
							if (exceedsFreelimit(entry)) {
								breakDownloadMessage = app.getString(R.string.free_version_message, DownloadActivity.MAXIMUM_AVAILABLE_FREE_DOWNLOADS
										+ "");
								break downloadCycle;
							}
							setTag(entry);
							boolean result = downloadFile(entry, filesToReindex, forceWifi);
							success = result || success;
							if (result) {
								if (DownloadActivityType.isCountedInDownloads(entry.item)) {
									downloads.set(downloads.get() + 1);
								}
								if (entry.existingBackupFile != null) {
									Algorithms.removeAllFiles(entry.existingBackupFile);
								}
//								trackEvent(entry);
								publishProgress(entry);
							}
						}
						if (success) {
							entriesToDownload.remove(file);
						}
					}

				}
				String warn = reindexFiles(filesToReindex);
				if (breakDownloadMessage != null) {
					if (warn != null) {
						warn = breakDownloadMessage + "\n" + warn;
					} else {
						warn = breakDownloadMessage;
					}
				}
				updateLoadedFiles();
				return warn;
			} catch (InterruptedException e) {
				LOG.info("Download Interrupted");
				// do not dismiss dialog
			}
			return null;
		}

		private boolean exceedsFreelimit(DownloadEntry entry) {
			return Version.isFreeVersion(app) &&
					DownloadActivityType.isCountedInDownloads(entry.item) && downloads.get() >= DownloadActivity.MAXIMUM_AVAILABLE_FREE_DOWNLOADS;
		}

		private String reindexFiles(List<File> filesToReindex) {
			boolean vectorMapsToReindex = false;
			// reindex vector maps all at one time
			ResourceManager manager = app.getResourceManager();
			for (File f : filesToReindex) {
				if (f.getName().endsWith(IndexConstants.BINARY_MAP_INDEX_EXT)) {
					vectorMapsToReindex = true;
				}
			}
			List<String> warnings = new ArrayList<String>();
			manager.indexVoiceFiles(this);
			if (vectorMapsToReindex) {
				warnings = manager.indexingMaps(this);
			}
			List<String> wns = manager.indexAdditionalMaps(this);
			if (wns != null) {
				warnings.addAll(wns);
			}

			if (!warnings.isEmpty()) {
				return warnings.get(0);
			}
			return null;
		}

//		private void trackEvent(DownloadEntry entry) {
//			String v = Version.getAppName(app);
//			if (Version.isProductionVersion(app)) {
//				v = Version.getFullVersion(app);
//			} else {
//				v += " test";
//			}
//			new DownloadTracker().trackEvent(app, v, Version.getAppName(app),
//					entry.baseName, 1, app.getString(R.string.ga_api_key));
//		}

		@Override
		public void showWarning(String warning) {
			publishProgress(warning);
		}

		public boolean downloadFile(DownloadEntry de, List<File> filesToReindex, boolean forceWifi)
				throws InterruptedException {
			boolean res = false;
			if (de.isAsset) {
				try {
					if (ctx != null) {
						ResourceManager.copyAssets(ctx.getAssets(), de.assetName, de.targetFile);
						boolean changedDate = de.targetFile.setLastModified(de.dateModified);
						if (!changedDate) {
							LOG.error("Set last timestamp is not supported");
						}
						res = true;
					}
				} catch (IOException e) {
					LOG.error("Copy exception", e);
				}
			} else {
				res = downloadFileHelper.downloadFile(de, this, filesToReindex, this, forceWifi);
			}
			return res;
		}

		@Override
		protected void updateProgress(boolean updateOnlyProgress, Object tag) {
			if (uiActivity != null) {
				uiActivity.updateProgress(updateOnlyProgress, tag);
			}
		}
	}

	private boolean checkRunning() {
		if (getCurrentRunningTask() != null) {
			AccessibleToast.makeText(app, R.string.wait_current_task_finished, Toast.LENGTH_SHORT).show();
			return true;
		}
		return false;
	}

	public void runReloadIndexFiles() {
		checkRunning();
		final BasicProgressAsyncTask<Void, Void, IndexFileList> inst
				= new BasicProgressAsyncTask<Void, Void, IndexFileList>(ctx) {

			@Override
			protected void onPreExecute() {
				currentRunningTask.add(this);
				super.onPreExecute();
				this.message = ctx.getString(R.string.downloading_list_indexes);
			}

			@Override
			protected IndexFileList doInBackground(Void... params) {
				IndexFileList indexFileList = DownloadOsmandIndexesHelper.getIndexesList(ctx);
				if (indexFileList != null) {
					updateLoadedFiles();
					prepareFilesToUpdate();
					prepareData(indexFileList.getIndexFiles());
				}
				return indexFileList;
			}

			protected void onPostExecute(IndexFileList result) {
				notifyFilesToUpdateChanged();
				indexFiles = result;
				if (indexFiles != null && uiActivity != null) {
					boolean basemapExists = uiActivity.getMyApplication().getResourceManager().containsBasemap();
					IndexItem basemap = indexFiles.getBasemap();
					if (basemap != null) {
						String dt = uiActivity.getMyApplication().getResourceManager().getIndexFileNames().get(basemap.getTargetFileName());
						if (!basemapExists || !Algorithms.objectEquals(dt, basemap.getDate(dateFormat))) {
							List<DownloadEntry> downloadEntry = basemap
									.createDownloadEntry(uiActivity.getMyApplication(), DownloadActivityType.NORMAL_FILE,
											new ArrayList<DownloadEntry>());
							uiActivity.getEntriesToDownload().put(basemap, downloadEntry);
							AccessibleToast.makeText(uiActivity, R.string.basemap_was_selected_to_download,
									Toast.LENGTH_LONG).show();
							if (uiActivity instanceof DownloadActivity) {
								uiActivity.updateFragments();
							}
						}
					}
					if (indexFiles.isIncreasedMapVersion()) {
						showWarnDialog();
					}
				} else {
					AccessibleToast.makeText(ctx, R.string.list_index_files_was_not_loaded, Toast.LENGTH_LONG).show();
				}
				currentRunningTask.remove(this);
				if (uiActivity != null) {
					uiActivity.updateProgress(false, tag);
					runCategorization(uiActivity.getDownloadType());
					uiActivity.onCategorizationFinished();
				}
			}

			private void showWarnDialog() {
				AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
				builder.setMessage(R.string.map_version_changed_info);
				builder.setPositiveButton(R.string.button_upgrade_osmandplus, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pname:net.osmand.plus"));
						try {
							ctx.startActivity(intent);
						} catch (ActivityNotFoundException e) {
						}
					}
				});
				builder.setNegativeButton(R.string.shared_string_cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				});
				builder.show();

			}

			@Override
			protected void updateProgress(boolean updateOnlyProgress, Object tag) {
				if (uiActivity != null) {
					uiActivity.updateProgress(updateOnlyProgress, tag);
				}

			}
		};
		execute(inst);
	}

	public void runDownloadFiles() {
		if (checkRunning()) {
			return;
		}
		DownloadIndexesAsyncTask task = new DownloadIndexesAsyncTask(ctx);
		execute(task);
	}

	private <P> void execute(BasicProgressAsyncTask<P, ?, ?> task, P... indexItems) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, indexItems);
		} else {
			task.execute(indexItems);
		}
	}

	public Map<IndexItem, List<DownloadEntry>> getEntriesToDownload() {
		return entriesToDownload;
	}

	public void runCategorization(final DownloadActivityType type) {
		final BasicProgressAsyncTask<Void, Void, List<IndexItem>> inst
				= new BasicProgressAsyncTask<Void, Void, List<IndexItem>>(ctx) {
			private List<IndexItemCategory> cats;

			@Override
			protected void onPreExecute() {
				super.onPreExecute();
				currentRunningTask.add(this);
				this.message = ctx.getString(R.string.downloading_list_indexes);
				if (uiActivity != null) {
					uiActivity.updateProgress(false, tag);
				}
			}

			@Override
			protected List<IndexItem> doInBackground(Void... params) {
				final List<IndexItem> filtered = getFilteredByType();
				cats = IndexItemCategory.categorizeIndexItems(app, filtered);
				updateLoadedFiles();
				return filtered;
			}

			public List<IndexItem> getFilteredByType() {
				final List<IndexItem> filtered = new ArrayList<IndexItem>();
				List<IndexItem> cachedIndexFiles = getCachedIndexFiles();
				if (cachedIndexFiles != null) {
					for (IndexItem file : cachedIndexFiles) {
						if (file.getType() == type) {
							filtered.add(file);
						}
					}
				}
				return filtered;
			}


			@Override
			protected void onPostExecute(List<IndexItem> filtered) {
				prepareFilesToUpdate();
				notifyFilesToUpdateChanged();
				currentRunningTask.remove(this);
				if (uiActivity != null) {
					uiActivity.categorizationFinished(filtered, cats);
					uiActivity.updateProgress(false, tag);
				}
			}

			@Override
			protected void updateProgress(boolean updateOnlyProgress, Object tag) {
				if (uiActivity != null) {
					uiActivity.updateProgress(updateOnlyProgress, tag);
				}

			}

		};
		execute(inst);
	}

	private void prepareFilesToUpdate() {
		List<IndexItem> filtered = getCachedIndexFiles();
		if (filtered != null) {
			itemsToUpdate.clear();
			for (IndexItem item : filtered) {
				boolean outdated = checkIfItemOutdated(item);
				//include only activated files here
				if (outdated && indexActivatedFileNames.containsKey(item.getTargetFileName())) {
					itemsToUpdate.add(item);
				}
			}
		}
	}

	@UiThread
	private void notifyFilesToUpdateChanged() {
		List<IndexItem> filtered = getCachedIndexFiles();
		if (filtered != null) {
			if (uiActivity != null) {
				uiActivity.updateDownloadList(itemsToUpdate);
			}
		}
	}

	public boolean checkIfItemOutdated(IndexItem item) {
		boolean outdated = false;
		String sfName = item.getTargetFileName();
		java.text.DateFormat format = app.getResourceManager().getDateFormat();
		String date = item.getDate(format);
		String indexactivateddate = indexActivatedFileNames.get(sfName);
		String indexfilesdate = indexFileNames.get(sfName);
		if (date != null &&
				!date.equals(indexactivateddate) &&
				!date.equals(indexfilesdate)) {
			if ((item.getType() == DownloadActivityType.NORMAL_FILE && !item.extra) ||
					item.getType() == DownloadActivityType.ROADS_FILE ||
					item.getType() == DownloadActivityType.WIKIPEDIA_FILE ||
					item.getType() == DownloadActivityType.SRTM_COUNTRY_FILE) {
				outdated = true;
			} else {
				long itemSize = item.getContentSize();
				long oldItemSize = 0;
				if (item.getType() == DownloadActivityType.VOICE_FILE) {
					if (item instanceof AssetIndexItem) {
						File file = new File(((AssetIndexItem) item).getDestFile());
						oldItemSize = file.length();
					} else {
						File fl = new File(item.getType().getDownloadFolder(app, item), sfName + "/_config.p");
						if (fl.exists()) {
							oldItemSize = fl.length();
							try {
								InputStream is = ctx.getAssets().open("voice/" + sfName + "/config.p");
								if (is != null) {
									itemSize = is.available();
									is.close();
								}
							} catch (IOException e) {
							}
						}
					}
				} else {
					oldItemSize = app.getAppPath(item.getTargetFileName()).length();
				}


				if (itemSize != oldItemSize) {
					outdated = true;
				}
			}
		}
		return outdated;
	}

	private void updateFilesToUpdate() {
		List<IndexItem> stillUpdate = new ArrayList<IndexItem>();
		for (IndexItem item : itemsToUpdate) {
			String sfName = item.getTargetFileName();
			java.text.DateFormat format = app.getResourceManager().getDateFormat();
			String date = item.getDate(format);
			String indexactivateddate = indexActivatedFileNames.get(sfName);
			String indexfilesdate = indexFileNames.get(sfName);
			if (date != null &&
					!date.equals(indexactivateddate) &&
					!date.equals(indexfilesdate) &&
					indexActivatedFileNames.containsKey(sfName)) {
				stillUpdate.add(item);
			}
		}
		itemsToUpdate = stillUpdate;
		if (uiActivity != null) {
			uiActivity.updateDownloadList(itemsToUpdate);
		}
	}


	public boolean isDownloadRunning() {
		for (int i = 0; i < currentRunningTask.size(); i++) {
			if (currentRunningTask.get(i) instanceof DownloadIndexesAsyncTask) {
				return true;

			}
		}
		return false;
	}

	public BasicProgressAsyncTask<?, ?, ?> getCurrentRunningTask() {
		for (int i = 0; i < currentRunningTask.size(); ) {
			if (currentRunningTask.get(i).getStatus() == Status.FINISHED) {
				currentRunningTask.remove(i);
			} else {
				i++;
			}
		}
		if (currentRunningTask.size() > 0) {
			return currentRunningTask.get(0);
		}
		return null;
	}

	public double getAvailableSpace() {
		File dir = app.getAppPath("").getParentFile();
		double asz = -1;
		if (dir.canRead()) {
			StatFs fs = new StatFs(dir.getAbsolutePath());
			asz = (((long) fs.getAvailableBlocks()) * fs.getBlockSize()) / (1 << 20);
		}
		return asz;
	}

	public int getDownloads() {
		int i = 0;
		Collection<List<DownloadEntry>> vs = getEntriesToDownload().values();
		for (List<DownloadEntry> v : vs) {
			for (DownloadEntry e : v) {
				if (!currentDownloads.contains(e)) {
					i++;
				}
			}
		}
		if (!currentDownloads.isEmpty()) {
			i++;
		}
		return i;
	}

	public int getCountedDownloads() {
		int i = 0;
		Collection<List<DownloadEntry>> vs = getEntriesToDownload().values();
		for (List<DownloadEntry> v : vs) {
			for (DownloadEntry e : v) {
				if (DownloadActivityType.isCountedInDownloads(e.item)) {
					i++;
				}
			}
		}
		return i;
	}
}