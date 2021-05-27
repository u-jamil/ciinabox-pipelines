// @Grapes([
//       @Grab(group='com.amazonaws', module='aws-java-sdk-inspector', version='1.11.1020')
// ])
import com.amazonaws.services.inspector.AmazonInspector
import com.amazonaws.services.inspector.AmazonInspectorClientBuilder
import com.amazonaws.services.inspector.model.*
import java.util.concurrent.TimeUnit


def call(body) {
      def config = body
      // Query stack for inspector assessment template arn
      def template_arn = cloudformation(
        stackName: 'inspector-test',//config.stackName,
        queryType: 'output',
        query: 'TemplateArn',
        region: 'ap-southeast-2' //config.region,
      );
      println(template_arn)
      // Query stack for inspector assessment template test duration
      int testDuration = cloudformation(
        stackName: 'inspector-test',//config.stackName,
        queryType: 'output',
        query: 'TestDuration',
        region: 'ap-southeast-2' //config.region,
      ).toInteger();
      testDuration += 150 //pad the test length by 2 mins to account for startup/finshup time

      Date testStartTime = new Date()
      println(testStartTime)

      def assessmentRun = assessmentRun(template_arn)
      println(assessmentRun)

      def runStatus = getRunStatus(assessmentRun)
      println("runStatus: ${runStatus}")

      // Display the reamining time in a realtively informative way
      while (testDuration > 0) {
            if (testDuration <= 60) {
                  println("The test has ${testDuration} seconds left to run")
                  TimeUnit.SECONDS.sleep(20);
                  testDuration -= 20
            }
            else if (testDuration <= 300) {
                  println("The test has ${(testDuration/60)} minutes left to run")
                  TimeUnit.SECONDS.sleep(60);
                  testDuration -= 60
            }
            else if (testDuration > 300) {
                  println ("The test has ${(testDuration/60)} minutes left to run")
                  TimeUnit.SECONDS.sleep(300);
                  testDuration -= 300
            }
      }

      runStatus = getRunStatus(assessmentRun)
      println("runStatus: ${runStatus}")

      Date testCompleteTime = new Date()
      println(testCompleteTime)

      def assessmentArn = assessmentArn(assessmentRun, testStartTime, testCompleteTime)

      // // This was a test to check the status of the test and then only get the result when the test is complete, this does not work as of current as querying a running test (this way) produces an error.
      def testRunning = true
      while (testRunning.equals(true)) {
            def getResults = getResults(assessmentArn).toString()
            println("Pre Regex: ${getResults}")
            if ((getResults.contains("WORK_IN_PROGRESS")).equals(false)) {
                  testRunning = false
            }
      }

      def getResults = getResults(assessmentArn)
      println(getResults)
      def urlRegex = /http.*[^}]/
      def resutlUrl = (getResults =~ urlRegex)
      resutlUrl = resutlUrl[0]
      println(resutlUrl)
      println(resutlUrl.toURL().text)
      formatedResults(resutlUrl)
}

def assessmentRun(String template_arn) {
      def client = AmazonInspectorClientBuilder.standard().build()
      def request = new StartAssessmentRunRequest().withAssessmentTemplateArn(template_arn)
      def response = client.startAssessmentRun(request)
      return response.getAssessmentRunArn()
}

def assessmentArn(String arn, Date testStartTime, Date testCompleteTime) {
      def client = AmazonInspectorClientBuilder.standard().build()
      def timeRange = new TimestampRange().withBeginDate(testStartTime).withEndDate(testCompleteTime)
      def filter = new AssessmentRunFilter().withStartTimeRange(timeRange)
      def request = new ListAssessmentRunsRequest().withAssessmentTemplateArns(arn).withFilter(filter)
      def response = client.listAssessmentRuns(request)
      println(response)

      // Get the first returned arn by itself
      def regex = /arn.*]/
      response = (response =~ regex)
      response = response[0].toString()
      def length = response.length()
      response = response.substring(0, (length - 1))
      println(response)

      return response
}

def getResults(String result_arn) {
      def client = AmazonInspectorClientBuilder.standard().build()
      def request = new GetAssessmentReportRequest()
                        .withAssessmentRunArn(result_arn)
                        .withReportFileFormat('HTML') // HTML OR PDF
                        .withReportType('FULL') //FINDING OR FULL
      def response = client.getAssessmentReport(request)
      return response
}

def formatedResults(String fullResult) {
      // Check if there where Findings
      def regex = /A total of \d/
      def findings = (fullResult =~ regex)
      println('***********')
      println(finding)
      findings = findings[0]
      println(findings)
      findings = findings.replaceAll(/A total of /, '').toInteger() // Just get the total number of findings
      println(findings)

      if (findings >= 1) {
            println("Test(s) not passed ${findings} issue found")
            throw new GroovyRuntimeException("AMI failed insecptor test, see insepctor for details, AMI not pushed out")
      }
      else {
            println('Test(s) passed')
      }
}

def getRunStatus (String arn) {
      def client = AmazonInspectorClientBuilder.standard().build()
      def request = new DescribeAssessmentRunsRequest()
        .withAssessmentRunArns(arn)
      def response = client.describeAssessmentRuns(request)
      println("getRunStatus - response: ${response}")
      // Pull out just the state of the test
      def regex = /State: [A-Z_]*,/
      def state = (response =~ regex)
      state = state[0]
      println("getRunStatus - state pre cleanup: ${state}")
      // Cleanup the state string so its just the current state (not 'State: (state),')
      def length = state.length()
      state = state.substring(0, (length - 1))
      state = state.replace('State: ', '')
      println("getRunStatus - state post cleanup: ${state}")
      return state
}

// call([
//     region: 'ap-southeast-2',
//     stackName: 'inspector-test'
// ])
