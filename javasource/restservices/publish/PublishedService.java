package restservices.publish;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.ObjectState;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestRequestException.RestExceptionType;
import restservices.publish.RestServiceRequest.ContentType;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import communitycommons.XPath;
import communitycommons.XPath.IBatchProcessor;

public class PublishedService {

	ServiceDefinition def;

	public PublishedService(ServiceDefinition def) {
		this.def = def;
	}

	public String getConstraint(IContext context) {
		String constraint = def.getSourceConstraint() == null ? "" : def.getSourceConstraint();
		
		if (constraint.contains(RestServices.CURRENTUSER_TOKEN))
			constraint.replace(RestServices.CURRENTUSER_TOKEN, "'" + context.getSession().getUser().getMendixObject().getId() + "'");

		return constraint;
	}
	
	private IMetaObject sourceMetaEntity;

	private ChangeManager changeManager = new ChangeManager(this);
	
	public ChangeManager getChangeManager() {
		return changeManager;
	}

	public String getName() {
		return def.getName();
	}

	public String getSourceEntity() {
		return def.getSourceEntity();
	}
	
	public String getKeyAttribute() {
		return def.getSourceKeyAttribute();
	}

	public String getServiceUrl() {
		return Core.getConfiguration().getApplicationRootUrl() + "rest/" + getName() + "/";
	}

	private IMendixObject getObjectByKey(IContext context,
			String key) throws CoreException {
		String xpath = XPath.create(context, getSourceEntity()).eq(getKeyAttribute(), key).getXPath() + this.getConstraint(context);
		List<IMendixObject> results = Core.retrieveXPathQuery(context, xpath, 1, 0, ImmutableMap.of("id", "ASC"));
		return results.size() == 0 ? null : results.get(0);
	}

	private ObjectState getObjectStateByKey(IContext context, String key) throws CoreException {
		return XPath.create(context, ObjectState.class)
				.eq(ObjectState.MemberNames.key,key)
				.eq(ObjectState.MemberNames.ObjectState_ServiceObjectIndex, getChangeManager().getServiceObjectIndex())
				.first();
	}	

	public void serveListing(RestServiceRequest rsr, boolean includeData) throws Exception {
		if (!def.getEnableListing())
			throw new RestRequestException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");
		
		rsr.startDoc();
		rsr.datawriter.array();

		if (def.getEnableChangeTracking())
			serveListingFromIndex(rsr, includeData);
		else
			serveListingFromDB(rsr, includeData);

		rsr.datawriter.endArray();
		rsr.endDoc();
	}
	
	private void serveListingFromIndex(final RestServiceRequest rsr,
			final boolean includeData) throws CoreException {
		XPath.create(rsr.getContext(), ObjectState.class)
			.eq(ObjectState.MemberNames.ObjectState_ServiceObjectIndex, getChangeManager().getServiceObjectIndex())
			.eq(ObjectState.MemberNames.deleted, false)
			.addSortingAsc(ObjectState.MemberNames.key)
			.batch(RestServices.BATCHSIZE, new IBatchProcessor<ObjectState>() {

				@Override
				public void onItem(ObjectState item, long offset, long total)
						throws Exception {
					if (includeData)
						rsr.datawriter.value(new JSONObject(item.getjson()));
					else
						rsr.datawriter.value(getServiceUrl() + item.getkey());
				}
			});
	}

	private void serveListingFromDB(RestServiceRequest rsr, boolean includeData) throws Exception {
		IRetrievalSchema schema = Core.createRetrievalSchema(); 
		
		if (!includeData) {
			schema.addSortExpression(getKeyAttribute(), SortDirection.ASC);
			schema.addMetaPrimitiveName(getKeyAttribute());
			schema.setAmount(RestServices.BATCHSIZE);
		}
		
		long offset = 0;
		String xpath = "//" + getSourceEntity() + getConstraint(rsr.getContext());
		List<IMendixObject> result = null;
		
		do {
			schema.setOffset(offset);
			
			result = !includeData
					? Core.retrieveXPathQuery(rsr.getContext(), xpath, RestServices.BATCHSIZE, (int) offset, ImmutableMap.of(getKeyAttribute(), "ASC")) 
					: Core.retrieveXPathSchema(rsr.getContext(), xpath , schema, false);
		
			for(IMendixObject item : result) {
				if (!includeData) {
					if (!Utils.isValidKey(getKey(rsr.getContext(), item)))
						continue;
		
					rsr.datawriter.value(getObjecturl(rsr.getContext(), item));
				}
				else {
					IMendixObject view = convertSourceToView(rsr.getContext(), item);
					rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), view));
				}
			}
			
			offset += RestServices.BATCHSIZE;
		}
		while(!result.isEmpty());
	}
	
	public void serveGet(RestServiceRequest rsr, String key) throws Exception {
		if (!def.getEnableGet())
			throw new RestRequestException(RestExceptionType.METHOD_NOT_ALLOWED, "GET is not enabled for this service");
		
		if(def.getEnableChangeTracking())
			serveGetFromIndex(rsr, key);
		else
			serveGetFromDB(rsr, key);
	}

	
	private void serveGetFromIndex(RestServiceRequest rsr, String key) throws Exception {
		ObjectState source = getObjectStateByKey(rsr.getContext(), key);
		if (source == null || source.getdeleted()) 
			throw new RestRequestException(RestExceptionType.NOT_FOUND,	getName() + "/" + key);
		
		if (Utils.isNotEmpty(rsr.getETag()) && rsr.getETag().equals(source.getetag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		writeGetResult(rsr,key, new JSONObject(source.getjson()), source.getetag());
	}

	private void serveGetFromDB(RestServiceRequest rsr, String key) throws Exception {
		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		if (source == null) 
			throw new RestRequestException(
					keyExists(rsr.getContext(), key)? RestExceptionType.UNAUTHORIZED : RestExceptionType.NOT_FOUND,
					getName() + "/" + key);
		
		IMendixObject view = convertSourceToView(rsr.getContext(), source);
		JSONObject result = JsonSerializer.writeMendixObjectToJson(rsr.getContext(), view);
				
		String jsonString = result.toString(4);
		String eTag = Utils.getMD5Hash(jsonString);
		
		writeGetResult(rsr, key, result, eTag);
		rsr.getContext().getSession().release(view.getId());
	}

	private void writeGetResult(RestServiceRequest rsr, String key, JSONObject result, String eTag) {
		if (eTag.equals(rsr.getETag())) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		rsr.response.setHeader(RestServices.ETAG_HEADER, eTag);
		rsr.startDoc();

		if (rsr.getContentType() == ContentType.HTML)
			rsr.write("<h1>").write(getName()).write("/").write(key).write("</h1>");

		rsr.datawriter.value(result);
		rsr.endDoc();
	}
	
	public void serveDelete(RestServiceRequest rsr, String key, String etag) throws Exception {
		if (!def.getEnableDelete())
			throw new RestRequestException(RestExceptionType.METHOD_NOT_ALLOWED, "List is not enabled for this service");

		IMendixObject source = getObjectByKey(rsr.getContext(), key);
		
		if (source == null) 
			throw new RestRequestException(keyExists(rsr.getContext(), key) ? RestExceptionType.UNAUTHORIZED : RestExceptionType.NOT_FOUND, getName() + "/" + key);

		verifyEtag(rsr.getContext(), key, source, etag);
		
		rsr.getContext().startTransaction();
		
		if (Utils.isNotEmpty(def.getOnDeleteMicroflow()))
			Core.execute(rsr.getContext(), def.getOnDeleteMicroflow(), source);
		else
			Core.delete(rsr.getContext(), source);
		
		rsr.setStatus(204); //no content
		rsr.close();
	}
	
	public void servePost(RestServiceRequest rsr, JSONObject data) throws Exception {
		if (!def.getEnableCreate())
			throw new RestRequestException(RestExceptionType.METHOD_NOT_ALLOWED, "Create (POST) is not enabled for this service");

		rsr.getContext().startTransaction();

		IMendixObject target = Core.instantiate(rsr.getContext(), getSourceEntity());
		
		updateObject(rsr.getContext(), target, data);
		
		Object keyValue = target.getValue(rsr.getContext(), getKeyAttribute());
		String key = keyValue == null ? null : String.valueOf(keyValue);
		
		if (!Utils.isValidKey(key))
			throw new RuntimeException("Failed to serve POST request: microflow '" + def.getOnPublishMicroflow() + "' should have created a new key");
			
		rsr.setStatus(201); //created
		
		//question: write url, or write key?
		//rsr.write(getObjecturl(rsr.getContext(), target));
		rsr.write(key);
		rsr.close();
	}

	public void servePut(RestServiceRequest rsr, String key, JSONObject data, String etag) throws Exception {

		IContext context = rsr.getContext();
		context.startTransaction();
		
		IMendixObject target = getObjectByKey(context, key);
		
		if (!Utils.isValidKey(key))
			rsr.setStatus(404);
		else if (target == null) {
			if (keyExists(rsr.getContext(), key)){
				rsr.setStatus(400);
				rsr.close();
				return;
			}

			if (!def.getEnableCreate())
				throw new RestRequestException(RestExceptionType.METHOD_NOT_ALLOWED, "Create (PUT) is not enabled for this service");
			
			target = Core.instantiate(context, getSourceEntity());
			target.setValue(context, getKeyAttribute(), key);
			rsr.setStatus(201);
			
		}
		else {
			//already existing target
			if (!def.getEnableUpdate())
				throw new RestRequestException(RestExceptionType.METHOD_NOT_ALLOWED, "Update (PUT) is not enabled for this service");
			
			verifyEtag(rsr.getContext(), key, target, etag);
			rsr.setStatus(204);
		}
		
		updateObject(rsr.getContext(), target, data);
		rsr.close();
	}
	
	private boolean keyExists(IContext context, String key) throws CoreException {
		return getObjectByKey(context, key) != null; //context is always sudo, so that should work fine
	}

	private void updateObject(IContext context, IMendixObject target,
			JSONObject data) throws Exception, Exception {
		//TODO: make utility function in Consisency checker, or here
		Map<String, String> argtypes = Utils.getArgumentTypes(def.getOnUpdateMicroflow());
		
		if (argtypes.size() != 2)
			throw new RuntimeException("Expected exactly two arguments for microflow " + def.getOnUpdateMicroflow());
		
		//Determine argnames
		String viewArgName = null;
		String targetArgName = null;
		String viewArgType = null;
		for(Entry<String, String> e : argtypes.entrySet()) {
			if (e.getValue().equals(target.getType()))
				targetArgName = e.getKey();
			else if (Core.getMetaObject(e.getValue()) != null) {
				viewArgName = e.getKey();
				viewArgType = e.getValue();
			}
		}
		
		if (targetArgName == null || viewArgName == null || Core.getMetaObject(viewArgType).isPersistable())
			throw new RuntimeException("Microflow '" + def.getOnUpdateMicroflow() + "' should have one argument of type " + target.getType() + ", and one argument typed with an persistent entity");
		
		IMendixObject view = Core.instantiate(context, viewArgType);
		JsonDeserializer.readJsonDataIntoMendixObject(context, data, view, false);
		Core.commit(context, view);
		
		Core.execute(context, def.getOnUpdateMicroflow(), ImmutableMap.of(targetArgName, (Object) target, viewArgName, (Object) view));
	}

	private void verifyEtag(IContext context, String key, IMendixObject source, String etag) throws Exception {
		if (!this.def.getUseStrictVersioning())
			return;

		String currentETag;
		if (def.getEnableChangeTracking())
			currentETag = getObjectStateByKey(context, key).getetag();
		else {
			IMendixObject view = convertSourceToView(context, source);
			JSONObject result = JsonSerializer.writeMendixObjectToJson(context, view);
				
			String jsonString = result.toString(4);
			currentETag = Utils.getMD5Hash(jsonString);
		}
		
		if (!currentETag.equals(etag))
			throw new RestRequestException(RestExceptionType.CONFLICTED, "Update conflict detected, expected change based on version '" + currentETag + "', but found '" + etag + "'");
	}

	public IMetaObject getSourceMetaEntity() {
		if (this.sourceMetaEntity == null)
			this.sourceMetaEntity = Core.getMetaObject(getSourceEntity());
		return this.sourceMetaEntity;
	}
	
	public IMendixObject convertSourceToView(IContext context, IMendixObject source) throws CoreException {
		return (IMendixObject) Core.execute(context, def.getOnPublishMicroflow(), source);
	}

	public boolean identifierInConstraint(IContext c, IMendixIdentifier id) throws CoreException {
		if (this.getConstraint(c).isEmpty())
			return true;
		return Core.retrieveXPathQueryAggregate(c, "count(//" + getSourceEntity() + "[id='" + id.toLong() + "']" + this.getConstraint(c)) == 1;
	}

	public String getObjecturl(IContext c, IMendixObject obj) {
		//Pre: inConstraint is checked!, obj is not null
		String key = getKey(c, obj);
		if (!Utils.isValidKey(key))
			throw new IllegalStateException("Invalid key for object " + obj.toString());
		return this.getServiceUrl() + key;
	}

	public String getKey(IContext c, IMendixObject obj) {
		return obj.getMember(c, getKeyAttribute()).parseValueToString(c);
	}

	//TODO: replace with something recursive
	public Map<String, String> getPublishedMembers() {
		Map<String, String> res = new HashMap<String, String>();
/* TODO: determine published meta entity
 		for(IMetaPrimitive prim : this.getPublishMetaEntity().getMetaPrimitives())
 
			res.put(prim.getName(), prim.getType().toString());
		for(IMetaAssociation assoc : this.getPublishMetaEntity().getMetaAssociationsParent()) {
			PublishedService service = RestServices.getServiceForEntity(assoc.getChild().getName());
			if (service == null)
				continue;
			String name = Utils.getShortMemberName(assoc.getName());
			String type = assoc.getType() == AssociationType.REFERENCESET ? "[" + service.getServiceUrl() + "]" : service.getServiceUrl();
			res.put(name,  type);
		}
*/
		return res;
	}
	
	public void serveServiceDescription(RestServiceRequest rsr) {
		rsr.datawriter.object()
			.key("name").value(getName())
			.key("url").value(getServiceUrl())
			//TODO: export description
			.key("attributes").object();
		
		for(Entry<String, String> e : getPublishedMembers().entrySet()) 
			rsr.datawriter.key(e.getKey()).value(e.getValue());
		
		rsr.datawriter.endObject().endObject();
	}

	void debug(String msg) {
		if (RestServices.LOG.isDebugEnabled())
			RestServices.LOG.debug(msg);
	}

	public boolean isGetObjectEnabled() {
		return def.getEnableGet();
	}

	public boolean isWorldReadable() {
		return "*".equals(def.getAccessRole().trim());
	}

	public String getRequiredRole() {
		return def.getAccessRole().trim();
	}

	public void dispose() {
		this.changeManager.dispose();
	}

}

