package com.phoenix.adapter;

import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * The plugin contract every hardware class must implement to join the
 * Phoenix mesh (Design doc, Sec. 3 "Hardware Abstraction Layer" and
 * Sec. 6 "Other Design Artifacts").
 *
 * <p>{@link #registerNode()} returns a <em>capability template</em> for
 * that hardware class (cpu/memory/architecture/adapterType); {@link
 * com.phoenix.sim.NodeSimulator} (and later, real node agents) turn that
 * template into an actual {@link com.phoenix.model.NodeRegistrationRequest}.
 *
 * <p>{@link #executeTask} now takes a {@link NodeExecutionTarget} in
 * addition to the task. This is a deliberate refinement from the original
 * four-method contract: once multiple physical instances of the same
 * hardware class can be registered (e.g. two Linux boxes both resolving to
 * {@code LinuxAdapter}), the adapter needs to know *which* physical node it
 * is executing on, not just that it's "a Linux node". Phoenix Core still
 * depends only on this interface and never references {@code LinuxAdapter},
 * {@code AndroidAdapter}, or {@code Esp32Adapter} directly anywhere in
 * core/, scheduler/, dispatch/, or model/.
 */
public interface IHardwareAdapter {

    NodeInfo registerNode();

    ResourceStatus getResourceStatus();

    TaskResult executeTask(TaskDescriptor task, NodeExecutionTarget target);

    void reportResult(TaskResult result);
}
