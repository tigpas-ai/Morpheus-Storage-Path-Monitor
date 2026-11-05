package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs one of the fixed commands on one host and returns its output */
interface HostCommandRunner {
	/** @throws HostCommandException with a message that is safe to show */
	String run(Long hostId, String command, int timeoutSeconds)
}

/** Failure of a host command; the message never contains command output */
class HostCommandException extends RuntimeException {
	HostCommandException(String message) {
		super(message)
	}
}

/**
 * Runs a command through the Morpheus agent of the host ({@code executeCommandOnServer}); the agent runs it as root.
 * Callers only pass the constants {@link MultipathStatus#COMMAND}, {@link HostMounts#MOUNT_COMMAND} and
 * {@link HostMounts#WITHDRAW_COMMAND}; nothing from a request or setting is added to them.
 */
@Slf4j
class AgentCommandRunner implements HostCommandRunner {

	private final MorpheusContext morpheus

	AgentCommandRunner(MorpheusContext morpheus) {
		this.morpheus = morpheus
	}

	@Override
	String run(Long hostId, String command, int timeoutSeconds) {
		ComputeServer server = morpheus.services.computeServer.get(hostId)
		if (server == null) {
			throw new HostCommandException('host not found')
		}
		TaskResult result
		try {
			result = morpheus.executeCommandOnServer(server, command)
				.timeout(timeoutSeconds, TimeUnit.SECONDS)
				.blockingGet()
		} catch (Exception e) {
			if (isTimeout(e)) {
				throw new HostCommandException("no answer within ${timeoutSeconds} s".toString())
			}
			log.warn("Storage Path Monitor: command on host ${hostId} failed: ${e.class.simpleName}: ${e.message}")
			throw new HostCommandException('command could not be run on the host')
		}
		if (result == null) {
			throw new HostCommandException('command could not be run on the host')
		}
		if (!result.success) {
			String exitCode = result.exitCode?.trim()
			throw new HostCommandException(exitCode?.isInteger() && exitCode.toInteger() != 0
				? "command failed with exit code ${exitCode.toInteger()}".toString()
				: 'command failed on the host')
		}
		return result.output ?: (result.data instanceof String ? result.data : null)
	}

	private static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.cause) {
			if (t instanceof TimeoutException) {
				return true
			}
			if (t.cause == t) {
				break
			}
		}
		return false
	}
}
