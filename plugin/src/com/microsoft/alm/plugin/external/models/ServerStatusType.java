// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.plugin.external.models;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Server status types
 */
public enum ServerStatusType {
    ADD,
    RENAME,
    EDIT,
    DELETE,
    UNDELETE,
    LOCK,
    BRANCH,
    MERGE,
    UNKNOWN;

    public static final Logger logger = LoggerFactory.getLogger(ServerStatusType.class);
    public static final String SOURCE_RENAME = "source rename";

    /**
     * Figure out server status type from string
     *
     * @param statusString
     * @return
     */
    public static List<ServerStatusType> getServerStatusTypes(final String statusString) {
        final String[] args = StringUtils.split(statusString, ",");
        final List<ServerStatusType> types = new ArrayList<ServerStatusType>(args.length);

        for (int i = 0; i < args.length; i++) {
            if (StringUtils.equalsIgnoreCase(args[i].trim(), ADD.name())) {
                types.add(ADD);
            } else if (StringUtils.equalsIgnoreCase(args[i].trim(), DELETE.name())) {
                types.add(DELETE);
            } else if (StringUtils.equalsIgnoreCase(args[i].trim(), EDIT.name())) {
                types.add(EDIT);
            } else if (StringUtils.equalsIgnoreCase(args[i].trim(), RENAME.name()) || StringUtils.equalsIgnoreCase(args[i].trim(), SOURCE_RENAME)) {
                types.add(RENAME);
            } else if (StringUtils.equalsIgnoreCase(args[i].trim(), UNDELETE.name())) {
                types.add(UNDELETE);
            } else if (StringUtils.containsIgnoreCase(args[i].trim(), LOCK.name())) {
                types.add(LOCK);
            } else if (StringUtils.containsIgnoreCase(args[i].trim(), BRANCH.name())) {
                types.add(BRANCH);
            } else if (StringUtils.containsIgnoreCase(args[i].trim(), MERGE.name())) {
                types.add(MERGE);
            } else {
                logger.error("Undocumented status from server: " + args[i]);
                types.add(UNKNOWN);
            }
        }
        return types;
    }
}