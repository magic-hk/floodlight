/**
 *    Copyright 2013, Big Switch Networks, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.routing.web;

import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.types.DatapathId;
import org.python.google.common.collect.ImmutableList;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.floodlightcontroller.core.types.JsonObjectWrapper;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
import net.floodlightcontroller.flowdemand.MetricConstraint;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Path;

public class PathsResource extends ServerResource {

    protected static Logger log = LoggerFactory.getLogger(PathsResource.class);

    @Get("json")
    public Object retrieve(String fmJson) {
        IRoutingService routing =
                (IRoutingService)getContext().getAttributes().
                        get(IRoutingService.class.getCanonicalName());

        String url = getRequest().getResourceRef().toString();
        DatapathId srcDpid;
        DatapathId dstDpid;
        try {
            srcDpid = DatapathId.of((String) getRequestAttributes().get("src-dpid"));
            dstDpid = DatapathId.of((String) getRequestAttributes().get("dst-dpid"));
        } catch (Exception e) {
            return ImmutableMap.of("ERROR", "Could not parse source or destination DPID from URI");
        }     
        log.debug("Asking for paths from {} to {}", srcDpid, dstDpid);
        
        Integer numRoutes;
        try {
            numRoutes = Integer.parseInt((String) getRequestAttributes().get("num-paths"));
        } catch (NumberFormatException e) {
            return ImmutableMap.of("ERROR", "Could not parse number of paths from URI");
        }
        log.debug("Asking for {} paths", numRoutes);

        
        
        List<Path> results = null;
        try {
            if (url.contains("fast")) {
                results = routing.getPathsFast(srcDpid, dstDpid, numRoutes);
            } else if (url.contains("slow")) {
                results = routing.getPathsSlow(srcDpid, dstDpid, numRoutes);
            } else {
                results = routing.getPathsFast(srcDpid, dstDpid);
            }
        } catch (Exception e) {
            return JsonObjectWrapper.of(ImmutableList.of());
        }

        if (results == null || results.isEmpty()) {
            log.debug("No routes found in request for routes from {} to {}", srcDpid, dstDpid);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Got {} routes from {} to {}", new Object[] { results.size(), srcDpid, dstDpid });
                log.debug("These are the routes ---------------------------");
                log.debug("{}", results);
                log.debug("------------------------------------------------");
            }

            return JsonObjectWrapper.of(results);
        }
        return JsonObjectWrapper.of(ImmutableList.of());
    }
    
    @Post
    public Object retrieveMetrics(String fmJson) {
        IRoutingService routing =
                (IRoutingService)getContext().getAttributes().
                        get(IRoutingService.class.getCanonicalName());
        JsonObject json = new JsonParser().parse(fmJson).getAsJsonObject();
        String url = getRequest().getResourceRef().toString();
        DatapathId srcDpid;
        DatapathId dstDpid;
        try {
            srcDpid = DatapathId.of(json.get("src-dpid").getAsString());
            dstDpid = DatapathId.of(json.get("dst-dpid").getAsString());
        } catch (Exception e) {
            return ImmutableMap.of("ERROR", "Could not parse source or destination DPID from JSON");
        }     
        log.debug("Asking for paths from {} to {}", srcDpid, dstDpid);
        
        Integer numRoutes;
        try {
            numRoutes = Integer.parseInt(json.get("num-paths").getAsString());
        } catch (NumberFormatException e) {
            return ImmutableMap.of("ERROR", "Could not parse number of paths from JSON");
        }
        log.debug("Asking for {} paths", numRoutes);
        
        Map<QOS_METRIC,MetricConstraint> metrics;
        try{
        	JsonArray jsmetrics= json.get("qos_metrics").getAsJsonArray();
        	metrics = MetricConstraint.parseFromJsonArray(jsmetrics);
        } catch (Exception e) {
        	log.info("-----e {}",e);
            return ImmutableMap.of("ERROR", "Could not parse qos_mertics from JSON");
        }   
        
        
        List<Path> results = null;
        try {
            if (url.contains("fast")) {
                results = routing.getMetricsConstraintPathsFast(srcDpid, dstDpid, numRoutes,metrics);
            } else if (url.contains("slow")) {
            	log.info("-----slow");
                results = routing.getMetricsConstraintPathsSlow(srcDpid, dstDpid, numRoutes,metrics);
            } 
        } catch (Exception e) {
            return JsonObjectWrapper.of(ImmutableList.of());
        }

        if (results == null || results.isEmpty()) {
            log.debug("No routes found in request for routes from {} to {}", srcDpid, dstDpid);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Got {} routes from {} to {}", new Object[] { results.size(), srcDpid, dstDpid });
                log.debug("These are the routes ---------------------------");
                log.debug("{}", results);
                log.debug("------------------------------------------------");
            }

            return JsonObjectWrapper.of(results);
        }
        return JsonObjectWrapper.of(ImmutableList.of());
    }
    
}