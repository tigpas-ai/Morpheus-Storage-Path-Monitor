package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class AgentCommandRunnerSpec extends Specification {

	ComputeServer server = new ComputeServer(id: 11L)
	MorpheusSynchronousComputeServerService servers = Stub {
		get(11L) >> server
		get(_) >> null
	}
	MorpheusContext morpheus = Mock {
		getServices() >> Stub(MorpheusServices) { getComputeServer() >> servers }
	}
	AgentCommandRunner runner = new AgentCommandRunner(morpheus)

	def 'runs exactly the fixed command and returns its output'() {
		when:
		String output = runner.run(11L, 5)

		then:
		1 * morpheus.executeCommandOnServer(server, 'hvmcli storage multipath status --json') >>
			Single.just(new TaskResult(success: true, output: '{"data":[]}'))
		output == '{"data":[]}'
	}

	@Unroll
	def 'unsuccessful result with exit code #code → #message'() {
		given:
		morpheus.executeCommandOnServer(_, _) >> Single.just(new TaskResult(success: false, exitCode: code, output: 'secret'))

		when:
		runner.run(11L, 5)

		then:
		HostCommandException e = thrown()
		e.message == message

		where:
		code     || message
		'127'    || 'command failed with exit code 127'
		' 1 '    || 'command failed with exit code 1'
		'0'      || 'command failed on the host'
		null     || 'command failed on the host'
		'secret' || 'command failed on the host'
	}

	def 'no answer in time is a timeout'() {
		given:
		morpheus.executeCommandOnServer(_, _) >> Single.never()

		when:
		runner.run(11L, 1)

		then:
		HostCommandException e = thrown()
		e.message == 'no answer within 1 s'
	}

	def 'agent errors do not leak details'() {
		given:
		morpheus.executeCommandOnServer(_, _) >> Single.error(new IllegalStateException('secret'))

		when:
		runner.run(11L, 5)

		then:
		HostCommandException e = thrown()
		e.message == 'command could not be run on the host'
	}

	def 'unknown host'() {
		when:
		runner.run(12L, 5)

		then:
		HostCommandException e = thrown()
		e.message == 'host not found'
	}
}
