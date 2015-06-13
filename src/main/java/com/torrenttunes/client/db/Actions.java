package com.torrenttunes.client.db;
import static com.torrenttunes.client.db.Tables.*;

import java.io.File;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.lang3.StringEscapeUtils;
import org.codehaus.jackson.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.frostwire.jlibtorrent.TorrentAlertAdapter;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.torrenttunes.client.DataSources;
import com.torrenttunes.client.LibtorrentEngine;
import com.torrenttunes.client.Tools;
import com.torrenttunes.client.TorrentStats;
import com.torrenttunes.client.ScanDirectory.ScanInfo;
import com.torrenttunes.client.ScanDirectory.ScanStatus;
import com.torrenttunes.client.db.Tables.Library;
import com.torrenttunes.client.db.Tables.Settings;
public class Actions {
	
	static final Logger log = LoggerFactory.getLogger(Actions.class);
	
	public static Library saveSongToLibrary(String mbid, String torrentPath, String infoHash,
			String filePath, String artist, String artistMbid, String album, String albumMbid,
			String title, 
			String albumCoverArtUrl, String albumCoverArtThumbnailLarge,
			String albumCoverArtThumbnailSmall, Long durationMS, Integer trackNumber, String year) {
		
		
		Library library = LIBRARY.create("mbid", mbid,
				"torrent_path", torrentPath,
				"info_hash", infoHash,
				"file_path", filePath,
				"artist", StringEscapeUtils.escapeHtml4(artist),
				"artist_mbid", artistMbid,
				"album", StringEscapeUtils.escapeHtml4(album),
				"album_mbid", albumMbid,
				"title", StringEscapeUtils.escapeHtml4(title),
				"duration_ms", durationMS,
				"track_number", trackNumber,
				"year", year,
				"album_coverart_url", albumCoverArtUrl,
				"album_coverart_thumbnail_large", albumCoverArtThumbnailLarge,
				"album_coverart_thumbnail_small", albumCoverArtThumbnailSmall);
		
		library.saveIt();
	
		
		return library;
		
	}
	
	public static void clearAndSavePlayQueue(JsonNode on) {
		QUEUE_TRACK.deleteAll();
		for (int i = 0; i < on.size(); i++) {
			JsonNode track = on.get(i);
			Integer libraryId = track.get("id").asInt();
			QUEUE_TRACK.createIt("library_id", libraryId);
		}
	}

	public static String saveSettings(String storagePath, Integer maxDownloadSpeed,
			Integer maxUploadSpeed, Integer maxCacheSize) {
		
		Settings s = SETTINGS.findFirst("id = ?", 1);
		
		StringBuilder message = new StringBuilder();
		
		// If storage path has changed, you need to move all the torrents in that cache directory,
		// to wherever they want, and change their 
		// TODO
		
		String currentStoragePath = s.getString("storage_path");
		if (!storagePath.equals(currentStoragePath)) {
			message.append("Moved all music files from " + currentStoragePath  + " to " + storagePath);
		}
		
		s.set("max_download_speed", maxDownloadSpeed,
				"max_upload_speed", maxUploadSpeed,
				"max_cache_size_mb", maxCacheSize);
		s.saveIt();
		
		LibtorrentEngine lte = LibtorrentEngine.INSTANCE;
		maxDownloadSpeed = (maxDownloadSpeed != -1) ? maxDownloadSpeed : 0;
		maxUploadSpeed = (maxUploadSpeed != -1) ? maxUploadSpeed : 0;
		lte.getSessionSettings().setDownloadRateLimit(1000 * maxDownloadSpeed);
		lte.getSessionSettings().setUploadRateLimit(1000 * maxUploadSpeed);
		lte.updateSettings();
		
		message.append("Settings Saved");
		
		return message.toString();

		
	}
	
public static String downloadTorrent(String infoHash) throws IOException, InterruptedException {
		
		
		String json = null;
		LibtorrentEngine lte = LibtorrentEngine.INSTANCE;
		
		Library track;
		String torrentPath = DataSources.TORRENTS_DIR() + "/" + infoHash + ".torrent";

		// Fetch the .torrent file it to a file, save it to the torrents dir
		Tools.httpSaveFile(DataSources.TORRENT_DOWNLOAD_URL(infoHash), torrentPath);

		// Fetch the .torrent file json info
		String trackJson = Tools.httpGetString(DataSources.TORRENT_INFO_DOWNLOAD_URL(infoHash));

		JsonNode jsonNode = Tools.jsonToNode(trackJson);

		// Set up all the necessary vars from the jsonInfo
		String songMbid = jsonNode.get("song_mbid").asText();
		String songTitle = jsonNode.get("title").asText();
		Long duration = jsonNode.get("duration_ms").asLong();
		Integer trackNumber = jsonNode.get("track_number").asInt();
		String album = jsonNode.get("album").asText();
		String albumMbid = jsonNode.get("release_mbid").asText();
		String artist = jsonNode.get("artist").asText();
		String artistMbid = jsonNode.get("artist_mbid").asText();
		String year = jsonNode.get("year").asText();
		String coverArt = jsonNode.get("album_coverart_url").asText();
		String thumbnailLarge = jsonNode.get("album_coverart_thumbnail_large").asText();
		String thumbnailSmall = jsonNode.get("album_coverart_thumbnail_small").asText();



		// add the torrent file(saving to the storage dir), scan info, and start seeding it					
		TorrentHandle torrent = lte.addTorrent(
				new File(DataSources.MUSIC_STORAGE_PATH), new File(torrentPath));

		String audioFilePath = DataSources.CACHE_FILE(torrent.getName());

		// Set up the scanInfo
		ScanInfo si = ScanInfo.create(new File(audioFilePath));
		si.setStatus(ScanStatus.Seeding);
		si.setMbid(songMbid);
		lte.getScanInfos().add(si); // TODO not sure about this one


		// Need to add the # of peers, and block IO until download is done, or times out
		final CountDownLatch signal = new CountDownLatch(1);


		// If it takes more than 30 seconds to download a file, then set no peers,
		// and throw an error
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				signal.countDown();
				String resp = Tools.httpSimplePost(DataSources.SEEDER_INFO_UPLOAD(infoHash, "0-0"));
				log.info("Seeder post response: " + resp);
			}

		}, 40000);

		lte.getSession().addListener(new TorrentAlertAdapter(torrent) {
			private Timer timer;

			@Override
			public void torrentFinished(TorrentFinishedAlert alert) {


				// Save the track to your DB
				Tools.dbInit();
				Library newTrack = Actions.saveSongToLibrary(songMbid, 
						torrentPath, 
						infoHash,
						audioFilePath, 
						artist, 
						artistMbid,
						album,
						albumMbid,
						songTitle, 
						coverArt,
						thumbnailLarge, 
						thumbnailSmall,
						duration,
						trackNumber,
						year);

				newTrack.saveIt();
				Tools.dbClose();

				TorrentStats ts = TorrentStats.create(torrent);
				log.info(ts.toString());



				// Once the torrent's finished, save the number of peers:
				String resp = Tools.httpGetString(DataSources.SEEDER_INFO_UPLOAD(
						infoHash, ts.getPeers()));
				log.info("Seeder post response: " + resp);
				signal.countDown();
				timer.cancel();



			}


			private TorrentAlertAdapter init(Timer t) {
				timer = t;
				return this;
			}



		}.init(timer));



		signal.await();

		// Get the json for the saved track
		Tools.dbInit();
		track = LIBRARY.findFirst("info_hash = ?", infoHash);
		Tools.dbClose();

		// if it wasn't succesful(IE no peers found or > 40 seconds)
		if (track == null) {
			throw new NoSuchElementException("No peers found for " + 
					artist + " - " + songTitle);
		} else {
			json = track.toJson(false);
		}
		return json;
	}



	public static Boolean spaceFreeInStoragePath() {
		
		// Check to make sure you have space in the cache
		Tools.dbInit();
		Settings settings = SETTINGS.findFirst("id = ?", 1);
		Tools.dbClose();
		
		Integer settingsFreeSpaceMB = settings.getInteger("max_cache_size_mb");
		settingsFreeSpaceMB = (settingsFreeSpaceMB != -1) ? settingsFreeSpaceMB : Integer.MAX_VALUE;
		
		Long temp = Math.round(Tools.folderSize(new File(DataSources.MUSIC_STORAGE_PATH)) * 0.000001);
		Integer storageFolderSizeMB = temp.intValue();
		
		Boolean spaceFree = (storageFolderSizeMB < settingsFreeSpaceMB) && 
				(new File("/").getUsableSpace() > 0);
		
		return spaceFree;
	}

	

}
