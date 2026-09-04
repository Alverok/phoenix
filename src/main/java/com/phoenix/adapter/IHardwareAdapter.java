package com.phoenix.adapter;

import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * The plugin contract every hardware class must implement to join the
 * Phoenix mesh (Design doc, Sec. 3 "Hardware Abstraction Layer" and
 * Sec. 6 "Other Design Artifacts").
 *
 * <p>Phoenix Core depends only on this interface. It never references
 * {@code LinuxAdapter}, {@code AndroidAdapter}, or {@code Esp32Adapter}
 * directly anywhere in core/ or model/ — that separation is the entire
 * IoC claim this project is built to prove.
 */
public interface IHardwareAdapter {

    NodeInfo registerNode();

    ResourceStatus getResourceStatus();

    TaskResult executeTask(TaskDescriptor task);

    void reportResult(TaskResult result);
}
