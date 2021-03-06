/*
 * Copyright 2015 Bounce Storage, Inc. <info@bouncestorage.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bouncestorage.swiftproxy.v1;

import static java.util.Objects.requireNonNull;

import static com.google.common.base.Throwables.propagate;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import com.bouncestorage.swiftproxy.BlobStoreResource;
import com.bouncestorage.swiftproxy.BounceResourceConfig;
import com.bouncestorage.swiftproxy.Utils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.DateSerializer;
import com.google.common.base.Strings;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.options.ListContainerOptions;

@Path("/v1/{account}/{container}")
public final class ContainerResource extends BlobStoreResource {

    private void createContainer(String authToken, String container) {
        if (container.length() > InfoResource.CONFIG.swift.max_container_name_length) {
            throw new BadRequestException("container name too long");
        }

        getBlobStore(authToken).get(container).createContainerInLocation(null, container);
    }

    @POST
    public Response postContainer(@NotNull @PathParam("container") String container,
                                  @HeaderParam("X-Auth-Token") String authToken,
                                  @HeaderParam("X-Container-Read") String readACL,
                                  @HeaderParam("X-Container-write") String writeACL,
                                  @HeaderParam("X-Container-Sync-To") String syncTo,
                                  @HeaderParam("X-Container-Sync-Key") String syncKey,
                                  @HeaderParam("X-Versions-Location") String versionsLocation,
                                  @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
                                  @HeaderParam("X-Detect-Content-Type") boolean detectContentType,
                                  @HeaderParam(HttpHeaders.IF_NONE_MATCH) String ifNoneMatch) {
        createContainer(authToken, container);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @PUT
    public Response putContainer(@NotNull @PathParam("container") String container,
                                  @HeaderParam("X-Auth-Token") String authToken,
                                  @HeaderParam("X-Container-Read") String readACL,
                                  @HeaderParam("X-Container-write") String writeACL,
                                  @HeaderParam("X-Container-Sync-To") String syncTo,
                                  @HeaderParam("X-Container-Sync-Key") String syncKey,
                                  @HeaderParam("X-Versions-Location") String versionsLocation,
                                  @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
                                  @HeaderParam("X-Detect-Content-Type") boolean detectContentType,
                                  @HeaderParam(HttpHeaders.IF_NONE_MATCH) String ifNoneMatch) {
        Response.Status status;
        BlobStore store = getBlobStore(authToken).get(container);

        if (store.containerExists(container)) {
            status = Response.Status.ACCEPTED;
        } else {
            createContainer(authToken, container);
            status = Response.Status.CREATED;
        }

        return Response.status(status).build();
    }

    @DELETE
    public Response deleteContainer(@NotNull @PathParam("container") String container,
                                    @HeaderParam("X-Auth-Token") String authToken) {
        BlobStore store = getBlobStore(authToken).get(container);
        if (!store.containerExists(container)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (store.deleteContainerIfEmpty(container)) {
            return Response.noContent().build();
        } else {
            return Response.status(Response.Status.CONFLICT)
                    .entity("<html><h1>Conflict</h1><p>There was a conflict when trying to complete your request.</p></html>")
                    .build();
        }
    }

    @HEAD
    public Response headContainer(@NotNull @PathParam("container") String container,
                                  @HeaderParam("X-Auth-Token") String authToken,
                                  @HeaderParam("X-Newest") @DefaultValue("false") boolean newest) {
        BlobStore store = getBlobStore(authToken).get(container);
        if (!store.containerExists(container)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        long objectCount = -1;
        String provider = store.getContext().unwrap().getId();
        if (provider.equals("transient") || provider.equals("openstack-swift")) {
            objectCount = store.countBlobs(container);
        }

        return Response.status(Response.Status.NO_CONTENT).entity("")
                .header("X-Container-Object-Count", objectCount)
                .header("X-Container-Bytes-Used", 0)  // TODO: bogus value
                .header("X-Versions-Location", "")
                .header("X-Timestamp", -1)
                .header("X-Trans-Id", -1)
                .header("Accept-Ranges", "bytes")
                .build();
    }

    private String contentType(StorageMetadata meta) {
        if (meta instanceof BlobMetadata) {
            String contentType = ((BlobMetadata) meta).getContentMetadata().getContentType();
            if (contentType != null && !contentType.isEmpty()) {
                return contentType;
            }
        }
        if (meta.getType().equals(StorageType.RELATIVE_PATH) || meta.getName().endsWith("/")) {
            return "application/directory";
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @GET
    public Response listContainer(@NotNull @PathParam("container") String container,
                                  @HeaderParam("X-Auth-Token") String authToken,
                                  @QueryParam("limit") Integer limit,
                                  @QueryParam("marker") String marker,
                                  @QueryParam("end_marker") String endMarker,
                                  @QueryParam("format") Optional<String> format,
                                  @QueryParam("prefix") String prefixParam,
                                  @QueryParam("delimiter") String delimiterParam,
                                  @QueryParam("path") String path,
                                  @HeaderParam("X-Newest") @DefaultValue("false") boolean newest,
                                  @HeaderParam("Accept") Optional<String> accept) {
        BlobStore store = getBlobStore(authToken).get(container);
        if (!store.containerExists(container)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        ListContainerOptions options = new ListContainerOptions();
        if (!Strings.isNullOrEmpty(marker)) {
            options.afterMarker(marker);
        }

        if (Strings.isNullOrEmpty(delimiterParam) && path == null) {
            options.recursive();
        }

        if (!Strings.isNullOrEmpty(delimiterParam)) {
            options.delimiter(delimiterParam);
        }

        if (!Strings.isNullOrEmpty(prefixParam)) {
            options.prefix(prefixParam);
        }

        if (path != null) {
            if (path.equals("/")) {
                options.prefix("/");
                options.delimiter("/");
            } else {
                options.inDirectory(path);
            }
        }

        logger.info("list: {} marker={} prefix={}", options, options.getMarker(), prefixParam);
        List<ObjectEntry> entries = StreamSupport.stream(
                Utils.crawlBlobStore(store, container, options).spliterator(), false)
                .peek(meta -> logger.debug("meta: {}", meta))
                //.filter(meta -> (prefix == null || meta.getName().startsWith(prefix)))
                //.filter(meta -> delimFilter(meta.getName(), delim_filter))
                .filter(meta -> endMarker == null || meta.getName().compareTo(endMarker) < 0)
                .limit(limit == null ? InfoResource.CONFIG.swift.container_listing_limit : limit)
                .map(meta -> new ObjectEntry(meta.getName(), meta.getETag(),
                        meta.getSize() == null ? 0 : meta.getSize(),
                        contentType(meta), meta.getLastModified()))
                .collect(Collectors.toList());

        MediaType formatType;
        if (format.isPresent()) {
            formatType = BounceResourceConfig.getMediaType(format.get());
        } else if (accept.isPresent()) {
            formatType = MediaType.valueOf(accept.get());
        } else {
            formatType = MediaType.TEXT_PLAIN_TYPE;
        }

        if (store.getContext().unwrap().getId().equals("transient")) {
            entries.forEach(entry -> {
                try {
                    entry.name = URLDecoder.decode(entry.name, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw propagate(e);
                }
            });
        }

        // XXX semi-bogus value
        long totalBytes = entries.stream().mapToLong(e -> e.bytes).sum();

        ContainerRoot root = new ContainerRoot();
        root.name = container;
        root.object = entries;
        return output(root, entries, formatType)
                .header("X-Container-Object-Count", entries.size())
                .header("X-Container-Bytes-Used", totalBytes)
                .header("X-Timestamp", -1)
                .header("X-Trans-Id", -1)
                .header("Accept-Ranges", "bytes")
                .build();

    }

    @XmlRootElement(name = "container")
    @XmlType
    static class ContainerRoot {
        @XmlElement
        List<ObjectEntry> object;
        @XmlAttribute
        private String name;
    }

    @XmlRootElement(name = "object")
    @XmlType
    static class ObjectEntry {
        static class SwiftDateSerializer extends DateSerializer {
            SwiftDateSerializer() {
                super(false, new SimpleDateFormat("yyyy-MM-dd'T'kk:mm:ss.SSSSSS"));
            }
        }

        @XmlElement
        String name;
        @XmlElement
        String hash;
        @XmlElement
        long bytes;
        @XmlElement
        String content_type;
        @JsonSerialize(using = SwiftDateSerializer.class)
        @XmlElement
        Date last_modified;

        // dummy
        ObjectEntry() {
        }

        @JsonCreator
        ObjectEntry(@JsonProperty("name") String name,
                           @JsonProperty("hash") String hash,
                           @JsonProperty("bytes") long bytes,
                           @JsonProperty("content_type") String content_type,
                           @JsonProperty("last_modified") Date last_modified) {
            this.name = requireNonNull(name);
            this.hash = hash == null ? "" : Utils.trimETag(hash);
            this.bytes = bytes;
            this.content_type = requireNonNull(content_type);
            this.last_modified = last_modified == null ? Date.from(Instant.EPOCH) : last_modified;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
