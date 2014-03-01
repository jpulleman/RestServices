package restservices.consume;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.FollowChangesState;
import restservices.util.JsonDeserializer;
import restservices.util.Utils;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import communitycommons.XPath;

public class ChangeFeedListener {
	private String url;
	private String onUpdateMF;
	private String onDeleteMF;
	private FollowChangesState state;
	private static Map<String, ChangeFeedListener> activeListeners = Collections.synchronizedMap(new HashMap<String, ChangeFeedListener>());
	volatile boolean cancelled = false;
	private Map<String, String> headers;
	private long	timeout;
	private volatile GetMethod currentRequest;
	private Thread listenerThread;
	
	
	//TODO: make status enabled in the UI
	private ChangeFeedListener(String collectionUrl, String onUpdateMF, String onDeleteMF, long timeout) throws Exception {
		this.url = collectionUrl;
		this.onUpdateMF = onUpdateMF;
		this.onDeleteMF = onDeleteMF;
		this.timeout = timeout;
		this.state = XPath.create(Core.createSystemContext(), FollowChangesState.class).findOrCreate(FollowChangesState.MemberNames.CollectionUrl, url);
	}
	
	public ChangeFeedListener follow() throws HttpException, IOException {
		synchronized (activeListeners) {
			if (activeListeners.containsKey(url))
				throw new IllegalStateException("Already listening to " + url);
		
			activeListeners.put(url, this);
		}
		
		
		headers = RestConsumer.nextHeaders.get();
		RestConsumer.nextHeaders.set(null);
		
		///TODO: clean this up, one thread should suffice, make sure no httpclient retries are used, or that only that is used..
		this.listenerThread = (new Thread() {
			
			private long nextRetryTime = 10000;
			@Override
			public void run() {
				while(!cancelled) {
					try {
						startConnection();
					}
					catch (Exception e)
					{
						RestServices.LOG.error("Failed to setup follow stream for " + getChangesRequestUrl(true) + ", retrying in " + nextRetryTime + "ms: " + e.getMessage());//, e);
						try {
							Thread.sleep(nextRetryTime);
							if (nextRetryTime < 60*60*1000)
								nextRetryTime *= 1.3;
						} catch (InterruptedException e1) {
							cancelled = true;
						} //Retry each 10 seconds
					}
				}
			}
		});
		
		listenerThread.setName("REST consume thread " + url);
		listenerThread.start();
		return this;
	}

	void startConnection() throws IOException,
			HttpException {
		String requestUrl = getChangesRequestUrl(true);
		
		GetMethod get = this.currentRequest = new GetMethod(requestUrl);
		get.setRequestHeader(RestServices.ACCEPT_HEADER, RestServices.TEXTJSON);
		
		//DefaultHttpRequestRetryHandler retryhandler = new DefaultHttpRequestRetryHandler(10, true);
		//get.setParameter(HttpMethodParams.RETRY_HANDLER, retryhandler);
		//TODO: auto retry
		
		RestConsumer.includeHeaders(get, headers);
		int status = RestConsumer.client.executeMethod(get);
		try {
			if (status != IMxRuntimeResponse.OK)
				throw new RuntimeException("Failed to setup stream to " + url +  ", status: " + status);

			InputStream inputStream = get.getResponseBodyAsStream();
		
			JSONTokener jt = new JSONTokener(inputStream);
			JSONObject instr = null;
			
			try {
				while(true) {
					instr = new JSONObject(jt);
					
					//TODO: should continue on exception in processChange and just notify about the missed change?
					processChange(instr);
				}
			}
			catch(InterruptedException e2) {
				cancelled = true;
				RestServices.LOG.warn("Changefeed interrupted", e2);
			}
			catch(Exception e) {
				//Not graceful disconnected?
				if (!cancelled && !(jt.end() && e instanceof JSONException))
					throw new RuntimeException(e);
			}
		}
		finally {
			get.releaseConnection();
		}
	}

	public String getChangesRequestUrl(boolean useFeed) {
		//TODO: use constants
		
		return Utils.appendParamToUrl(Utils.appendParamToUrl(
			Utils.appendSlashToUrl(url) + "changes/" + (useFeed ? "feed" : "list"),
			"since", String.valueOf((long) state.getRevision())),
			"timeout", String.valueOf(timeout));
	}

	void fetch() throws IOException, Exception {
		RestConsumer.readJsonObjectStream(getChangesRequestUrl(false), new Predicate<Object>() {

			@Override
			public boolean apply(Object data) {
				if (!(data instanceof JSONObject))
					throw new RuntimeException("Changefeed expected JSONObject, found " + data.getClass().getSimpleName());
				try {
					processChange((JSONObject) data);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				return true;
			}
			
		});
	}
	
	void processChange(JSONObject instr) throws Exception {
		IContext c = Core.createSystemContext();

		long revision = instr.getLong("rev"); //TODO: doublecheck this context remains valid..

		RestServices.LOG.info("Receiving update for " + url + " #" + revision + " object: '" + instr.getString("key") + "'"); 
		
		//TODO: use constants
		if (instr.getBoolean("deleted")) {
			Map<String, String> args = Utils.getArgumentTypes(onDeleteMF);
			if (args.size() != 1 || !"String".equals(args.values().iterator().next()))
				throw new RuntimeException(onDeleteMF + " should have one argument of type string");
			Core.execute(c, onDeleteMF, ImmutableMap.of(args.keySet().iterator().next(), (Object) instr.getString("key")));
		}
		else {
			IDataType type = Utils.getFirstArgumentType(onUpdateMF);
			if (!type.isMendixObject())
				throw new RuntimeException("First argument should be an Entity! " + onUpdateMF);

			IMendixObject target = Core.instantiate(c, type.getObjectType());
			JsonDeserializer.readJsonDataIntoMendixObject(c, instr.getJSONObject("data"), target, true);
			Core.commit(c, target);
			Core.execute(c, onUpdateMF, ImmutableMap.of(Utils.getArgumentTypes(onUpdateMF).keySet().iterator().next(), (Object) target));
		}
		
		
		if (revision <= state.getRevision()) 
			RestServices.LOG.warn("Received revision (" + revision + ") is smaller as latest known revision (" + state.getRevision() +"), probably the collections are out of sync?");
		
		state.setRevision(revision);
		state.commit();
	}
	
	private void close() {
		activeListeners.remove(url);
		cancelled = true;
		if (this.currentRequest != null)
			this.currentRequest.abort();
		else if (!this.listenerThread.isInterrupted()) //It might be waiting
			this.listenerThread.interrupt();
	}

	public static synchronized void follow(final String collectionUrl, final String updateMicroflow,
			final String deleteMicroflow, final long timeout) throws HttpException, IOException, Exception {
		new ChangeFeedListener(collectionUrl, updateMicroflow, deleteMicroflow, timeout).follow();
	}
	
	public static synchronized void unfollow(String collectionUrl) {
		if (activeListeners.containsKey(collectionUrl))
			activeListeners.remove(collectionUrl).close();
	}
	
	public static synchronized void fetch(String collectionUrl, String updateMicroflow, String deleteMicroflow) throws Exception {
		new ChangeFeedListener(collectionUrl, updateMicroflow, deleteMicroflow, 0L).fetch();
	}

	public static void resetState(String collectionUrl) throws CoreException {
		if (activeListeners.containsKey(collectionUrl))
			throw new IllegalStateException("Cannot reset state for collection '" + collectionUrl + "', there is an active listener. Please unfollow first");
		XPath.create(Core.createSystemContext(), FollowChangesState.class).eq(FollowChangesState.MemberNames.CollectionUrl, collectionUrl).deleteAll();
	}
}
