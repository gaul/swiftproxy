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

package com.bouncestorage.swiftproxy;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import org.glassfish.jersey.spi.ExtendedExceptionMapper;
import org.jclouds.blobstore.ContainerNotFoundException;

@Provider
public class ContainerNotFoundExceptionMapper implements ExtendedExceptionMapper<ContainerNotFoundException> {
    @Override
    public final Response toResponse(ContainerNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @Override
    public final boolean isMappable(ContainerNotFoundException e) {
        return true;
    }
}