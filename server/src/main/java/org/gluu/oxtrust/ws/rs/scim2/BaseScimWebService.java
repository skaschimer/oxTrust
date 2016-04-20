/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.ws.rs.scim2;

import javax.ws.rs.core.Response;

import com.unboundid.ldap.sdk.Filter;
import org.gluu.oxtrust.ldap.service.ApplianceService;
import org.gluu.oxtrust.model.GluuAppliance;
import org.gluu.oxtrust.model.GluuCustomPerson;
import org.gluu.oxtrust.model.scim.Error;
import org.gluu.oxtrust.model.scim.Errors;
import org.gluu.oxtrust.model.scim2.Constants;
import org.gluu.oxtrust.model.scim2.ErrorResponse;
import org.gluu.oxtrust.model.scim2.ErrorScimType;
import org.gluu.oxtrust.service.UmaAuthenticationService;
import org.gluu.oxtrust.service.antlr.scimFilter.ScimFilterParserService;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.log.Log;
import org.xdi.config.oxtrust.ApplicationConfiguration;
import org.xdi.ldap.model.GluuBoolean;
import org.xdi.ldap.model.SortOrder;
import org.xdi.ldap.model.VirtualListViewResponse;
import org.xdi.util.Pair;

import java.util.ArrayList;
import java.util.List;

import static org.gluu.oxtrust.model.scim2.Constants.DEFAULT_COUNT;
import static org.gluu.oxtrust.model.scim2.Constants.MAX_COUNT;
import static org.gluu.oxtrust.service.antlr.scimFilter.visitor.UserFilterVisitor.getUserLdapAttributeName;

/**
 * Base methods for SCIM web services
 * 
 * @author Yuriy Movchan Date: 08/23/2013
 */
public class BaseScimWebService {

	@Logger
	private Log log;

	@In(value = "#{oxTrustConfiguration.applicationConfiguration}")
	private ApplicationConfiguration applicationConfiguration;

	@In
	private ApplianceService applianceService;

	@In
	private UmaAuthenticationService umaAuthenticationService;

	@In
	private LdapEntryManager ldapEntryManager;

	@In
	private ScimFilterParserService scimFilterParserService;

	protected Response processAuthorization(String authorization) throws Exception {
		boolean authorized = getAuthorizedUser();
		if (!authorized) {
			if (!umaAuthenticationService.isEnabledUmaAuthentication()) {
				return getErrorResponse("User isn't authorized", Response.Status.FORBIDDEN.getStatusCode());
			}
			
			Pair<Boolean, Response> rptTokenValidationResult = umaAuthenticationService.validateRptToken(authorization, applicationConfiguration.getUmaResourceId(), applicationConfiguration.getUmaScope());
			if (rptTokenValidationResult.getFirst()) {
				if (rptTokenValidationResult.getSecond() != null) {
					return rptTokenValidationResult.getSecond();
				}
			} else {
				return getErrorResponse("User isn't authorized", Response.Status.FORBIDDEN.getStatusCode());
			}
		}
		return null;
	}

	protected boolean getAuthorizedUser() {
		try {
			GluuCustomPerson authUser = (GluuCustomPerson) Contexts.getSessionContext().get(OxTrustConstants.CURRENT_PERSON);

			if (authUser == null) {
				return false;
			}

			GluuAppliance appliance = applianceService.getAppliance();
			if (appliance == null) {
				return false;
			}

			if (!(GluuBoolean.TRUE.equals(appliance.getScimEnabled()) || GluuBoolean.ENABLED.equals(appliance.getScimEnabled()))) {
				return false;
			}

			return true;
		} catch (Exception ex) {
			log.error("Exception: ", ex);
			return false;
		}
	}

	public <T> List<T> search(String dn, Class<T> entryClass, String filterString, int startIndex, int count, String sortBy, String sortOrder, VirtualListViewResponse vlvResponse, String[] returnAttributes) throws Exception {

		log.info("----------");
		log.info(" ### RAW PARAMS ###");
		log.info(" filter string = " + filterString);
		log.info(" startIndex = " + startIndex);
		log.info(" count = " + count);
		log.info(" sortBy = " + sortBy);
		log.info(" sortOrder = " + sortOrder);

		Filter filter = null;
		if (filterString == null || (filterString != null && filterString.isEmpty())) {
			filter = Filter.create("inum=*");
		} else {
			filter = scimFilterParserService.createFilter(filterString, org.gluu.oxtrust.model.scim2.User.class);
		}

		count = (count < 1) ? DEFAULT_COUNT : count;
		count = (count > MAX_COUNT) ? MAX_COUNT : count;

		startIndex = (startIndex < 1) ? 1 : startIndex;

		sortBy = (sortBy == null || (sortBy != null && sortBy.isEmpty())) ? "displayName" : sortBy;
		if (entryClass.getName().equals(GluuCustomPerson.class.getName())) {
			sortBy = getUserLdapAttributeName(sortBy);
		}

		SortOrder sortOrderEnum = null;
		if (sortOrder != null && !sortOrder.isEmpty()) {
			sortOrderEnum = SortOrder.getByValue(sortOrder);
		} else if (sortBy != null && (sortOrder == null || (sortOrder != null && sortOrder.isEmpty()))) {
			sortOrderEnum = SortOrder.ASCENDING;
		} else {
			sortOrderEnum = SortOrder.ASCENDING;
		}

		log.info(" ### CONVERTED PARAMS ###");
		log.info(" parsed filter = " + filter.toString());
		log.info(" startIndex = " + startIndex);
		log.info(" count = " + count);
		log.info(" sortBy = " + sortBy);
		log.info(" sortOrder = " + sortOrderEnum.getValue());

		List<T> result = ldapEntryManager.findEntriesVirtualListView(dn, entryClass, filter, startIndex, count, sortBy, sortOrderEnum, vlvResponse, returnAttributes);

		log.info(" ### RESULTS INFO ###");
		log.info(" totalResults = " + vlvResponse.getTotalResults());
		log.info(" itemsPerPage = " + vlvResponse.getItemsPerPage());
		log.info(" startIndex = " + vlvResponse.getStartIndex());
		log.info("----------");

		return result;
	}

	protected Response getErrorResponse(String errMsg, int statusCode) {
		Errors errors = new Errors();
		Error error = new org.gluu.oxtrust.model.scim.Error(errMsg, statusCode, "");
		errors.getErrors().add(error);
		return Response.status(statusCode).entity(errors).build();
	}

	protected Response getErrorResponse(Response.Status status, ErrorScimType scimType, String detail) {

		log.info(" Error: " + scimType.getValue() + ", detail = " + detail + ", code = " + status.getStatusCode());

		ErrorResponse errorResponse = new ErrorResponse();

		List<String> schemas = new ArrayList<String>();
		schemas.add(Constants.ERROR_RESPONSE_URI);
		errorResponse.setSchemas(schemas);

		errorResponse.setStatus(String.valueOf(status.getStatusCode()));
		errorResponse.setScimType(scimType);
		errorResponse.setDetail(detail);

		return Response.status(status).entity(errorResponse).build();
	}
}
