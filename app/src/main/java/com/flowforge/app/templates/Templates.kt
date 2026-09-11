package com.flowforge.app.templates

import com.flowforge.app.model.*

object Templates {
    fun all(): List<Pair<String, () -> FlowDocument>> = listOf(
        "Basic flow" to { basic() },
        "Decision flow" to { decision() },
        "Server room" to { serverRoom() },
        "Database architecture" to { databaseArchitecture() },
        "Home / office network" to { homeOfficeNetwork() },
        "Creative" to { creative() },
        "Logical thought" to { logicalThought() },
        "Decision making" to { decisionMaking() },
        "Problem solving" to { problemSolving() },
        "Wiring diagram" to { wiring() },
        "Battery array" to { batteryArray() },
        "Troubleshooting" to { troubleshooting() },
        "Approval workflow" to { approval() },
        "Authentication" to { auth() },
        "Deployment" to { deployment() },
        "Incident response" to { incident() },
        "Security review" to { security() },
        "State machine" to { stateMachine() }
    )

    private fun el(d: FlowDocument, type: ElementType, x: Float, y: Float, label: String, notes: String = ""): FlowElement =
        FlowElement(type = type, x = x, y = y, label = label, notes = notes).also { d.elements += it }

    private fun link(d: FlowDocument, a: FlowElement, b: FlowElement, label: String = "", notes: String = "") {
        d.connections += FlowConnection(fromId = a.id, toId = b.id, label = label, notes = notes)
    }

    private fun basic(): FlowDocument {
        val d = FlowDocument("Basic flow")
        val s = el(d, ElementType.TERMINAL, 100f, 260f, "Start")
        val a = el(d, ElementType.PROCESS, 360f, 260f, "Prepare")
        val b = el(d, ElementType.PROCESS, 650f, 260f, "Process")
        val e = el(d, ElementType.TERMINAL, 940f, 260f, "End")
        link(d, s, a); link(d, a, b); link(d, b, e)
        return d
    }

    private fun decision(): FlowDocument {
        val d = FlowDocument("Decision flow")
        val s = el(d, ElementType.TERMINAL, 90f, 260f, "Start")
        val p = el(d, ElementType.PROCESS, 330f, 260f, "Check request")
        val q = el(d, ElementType.DECISION, 610f, 260f, "Valid?")
        val yes = el(d, ElementType.PROCESS, 900f, 120f, "Continue")
        val no = el(d, ElementType.PROCESS, 900f, 400f, "Fix problem")
        val e = el(d, ElementType.TERMINAL, 1190f, 120f, "Complete")
        link(d, s, p); link(d, p, q); link(d, q, yes, "Yes"); link(d, q, no, "No"); link(d, yes, e); link(d, no, p, "Retry")
        return d
    }

    private fun serverRoom(): FlowDocument {
        val d = FlowDocument("Server room")
        val internet = el(d, ElementType.SERVER, 80f, 260f, "Internet")
        val firewall = el(d, ElementType.SERVER, 350f, 260f, "Firewall")
        val switch = el(d, ElementType.SERVER, 620f, 260f, "Network switch")
        val app = el(d, ElementType.SERVER, 930f, 100f, "Application server")
        val db = el(d, ElementType.SERVER, 930f, 300f, "Database server")
        val backup = el(d, ElementType.SERVER, 930f, 500f, "Backup server")
        link(d, internet, firewall); link(d, firewall, switch)
        link(d, switch, app); link(d, switch, db); link(d, switch, backup)
        return d
    }

    private fun databaseArchitecture(): FlowDocument {
        val d = FlowDocument("Database architecture")
        val app = el(d, ElementType.PROCESS, 80f, 260f, "Application")
        val api = el(d, ElementType.SERVER, 370f, 260f, "Database API")
        val primary = el(d, ElementType.SERVER, 700f, 120f, "Primary database")
        val replica = el(d, ElementType.SERVER, 700f, 400f, "Read replica")
        val backup = el(d, ElementType.SERVER, 1030f, 260f, "Backup")
        link(d, app, api); link(d, api, primary, "Read / write"); link(d, api, replica, "Read"); link(d, primary, replica, "Replicate"); link(d, primary, backup, "Backup")
        return d
    }

    private fun homeOfficeNetwork(): FlowDocument {
        val d = FlowDocument("Home / office network")
        val isp = el(d, ElementType.SERVER, 80f, 260f, "Internet provider")
        val router = el(d, ElementType.SERVER, 390f, 260f, "Router / gateway")
        val wifi = el(d, ElementType.SERVER, 700f, 120f, "Wi-Fi")
        val lan = el(d, ElementType.SERVER, 700f, 400f, "Ethernet switch")
        val pc = el(d, ElementType.PROCESS, 1030f, 40f, "PCs / laptops")
        val phones = el(d, ElementType.PROCESS, 1030f, 200f, "Phones / tablets")
        val printer = el(d, ElementType.PROCESS, 1030f, 400f, "Printer")
        val nas = el(d, ElementType.SERVER, 1030f, 600f, "NAS / storage")
        link(d, isp, router); link(d, router, wifi); link(d, router, lan)
        link(d, wifi, pc); link(d, wifi, phones); link(d, lan, pc); link(d, lan, printer); link(d, lan, nas)
        return d
    }

    private fun creative(): FlowDocument {
        val d = FlowDocument("Creative")
        val challenge = el(d, ElementType.TERMINAL, 650f, 40f, "Challenge")
        val brainstorm = el(d, ElementType.PROCESS, 650f, 300f, "Brainstorm")
        val a = el(d, ElementType.PROCESS, 180f, 560f, "Idea A")
        val b = el(d, ElementType.PROCESS, 500f, 560f, "Idea B")
        val c = el(d, ElementType.PROCESS, 820f, 560f, "Idea C")
        val dIdea = el(d, ElementType.PROCESS, 1140f, 560f, "Idea D")
        val combine = el(d, ElementType.PROCESS, 650f, 840f, "Combine / expand")
        val refine = el(d, ElementType.DECISION, 650f, 1110f, "Promising?")
        val explore = el(d, ElementType.PROCESS, 350f, 1370f, "Explore further")
        val finalIdea = el(d, ElementType.TERMINAL, 950f, 1370f, "Refined idea")
        link(d, challenge, brainstorm)
        link(d, brainstorm, a); link(d, brainstorm, b); link(d, brainstorm, c); link(d, brainstorm, dIdea)
        link(d, a, combine); link(d, b, combine); link(d, c, combine); link(d, dIdea, combine)
        link(d, combine, refine); link(d, refine, finalIdea, "Yes"); link(d, refine, explore, "No"); link(d, explore, brainstorm, "New angle")
        return d
    }

    private fun logicalThought(): FlowDocument {
        val d = FlowDocument("Logical thought")
        val proposition = el(d, ElementType.TERMINAL, 650f, 40f, "Proposition")
        val evidence = el(d, ElementType.PROCESS, 300f, 330f, "Evidence")
        val assumptions = el(d, ElementType.PROCESS, 650f, 330f, "Assumptions")
        val counter = el(d, ElementType.PROCESS, 1000f, 330f, "Counterexamples")
        val test = el(d, ElementType.PROCESS, 650f, 620f, "Test the proposition")
        val result = el(d, ElementType.DECISION, 650f, 900f, "Evidence supports it?")
        val supported = el(d, ElementType.TERMINAL, 300f, 1180f, "Supported")
        val uncertain = el(d, ElementType.TERMINAL, 650f, 1180f, "Uncertain")
        val rejected = el(d, ElementType.TERMINAL, 1000f, 1180f, "Not supported")
        link(d, proposition, evidence); link(d, proposition, assumptions); link(d, proposition, counter)
        link(d, evidence, test); link(d, assumptions, test); link(d, counter, test); link(d, test, result)
        link(d, result, supported, "Yes"); link(d, result, uncertain, "Insufficient evidence"); link(d, result, rejected, "No")
        return d
    }

    private fun decisionMaking(): FlowDocument {
        val d = FlowDocument("Decision making")
        val goal = el(d, ElementType.TERMINAL, 650f, 40f, "Goal")
        val options = el(d, ElementType.PROCESS, 650f, 300f, "Identify options")
        val a = el(d, ElementType.PROCESS, 250f, 600f, "Option A")
        val b = el(d, ElementType.PROCESS, 650f, 600f, "Option B")
        val c = el(d, ElementType.PROCESS, 1050f, 600f, "Option C")
        val criteria = el(d, ElementType.PROCESS, 650f, 880f, "Compare against criteria")
        val choose = el(d, ElementType.DECISION, 650f, 1150f, "Best option?")
        val act = el(d, ElementType.PROCESS, 950f, 1430f, "Take action")
        val reconsider = el(d, ElementType.PROCESS, 350f, 1430f, "Reconsider")
        link(d, goal, options); link(d, options, a); link(d, options, b); link(d, options, c)
        link(d, a, criteria); link(d, b, criteria); link(d, c, criteria); link(d, criteria, choose)
        link(d, choose, act, "Yes"); link(d, choose, reconsider, "No clear winner"); link(d, reconsider, options, "New option")
        return d
    }

    private fun problemSolving(): FlowDocument {
        val d = FlowDocument("Problem solving")
        val problem = el(d, ElementType.TERMINAL, 70f, 260f, "Problem")
        val observe = el(d, ElementType.PROCESS, 340f, 260f, "Observe / gather facts")
        val causes = el(d, ElementType.PROCESS, 620f, 260f, "Possible causes")
        val test = el(d, ElementType.DECISION, 920f, 260f, "Cause fits evidence?")
        val fix = el(d, ElementType.PROCESS, 1210f, 120f, "Apply solution")
        val investigate = el(d, ElementType.PROCESS, 1210f, 420f, "Investigate more")
        val verify = el(d, ElementType.DECISION, 1500f, 260f, "Solved?")
        val done = el(d, ElementType.TERMINAL, 1780f, 120f, "Done")
        link(d, problem, observe); link(d, observe, causes); link(d, causes, test)
        link(d, test, fix, "Yes"); link(d, test, investigate, "No"); link(d, investigate, causes, "New evidence")
        link(d, fix, verify); link(d, verify, done, "Yes"); link(d, verify, observe, "No")
        return d
    }

    private fun wiring(): FlowDocument {
        val d = FlowDocument("Wiring diagram")
        val source = el(d, ElementType.SERVER, 80f, 260f, "Power source", "Battery or DC supply")
        val fuse = el(d, ElementType.PROCESS, 360f, 260f, "Fuse")
        val switch = el(d, ElementType.PROCESS, 620f, 260f, "Switch")
        val load = el(d, ElementType.SERVER, 900f, 260f, "Load")
        val returnNode = el(d, ElementType.PROCESS, 900f, 500f, "Return / negative")
        link(d, source, fuse, "+"); link(d, fuse, switch); link(d, switch, load, "Switched +"); link(d, load, returnNode, "-"); link(d, returnNode, source, "Return")
        return d
    }

    private fun batteryArray(): FlowDocument {
        val d = FlowDocument("Battery array")
        val b1 = el(d, ElementType.SERVER, 100f, 120f, "Battery 1")
        val b2 = el(d, ElementType.SERVER, 100f, 420f, "Battery 2")
        val b3 = el(d, ElementType.SERVER, 100f, 720f, "Battery 3")
        val positive = el(d, ElementType.PROCESS, 430f, 120f, "Positive bus")
        val negative = el(d, ElementType.PROCESS, 430f, 720f, "Negative bus")
        val fuse = el(d, ElementType.PROCESS, 720f, 120f, "Main fuse")
        val connector = el(d, ElementType.SERVER, 1020f, 120f, "Output connector")
        link(d, b1, positive, "+"); link(d, b2, positive, "+"); link(d, b3, positive, "+")
        link(d, b1, negative, "-"); link(d, b2, negative, "-"); link(d, b3, negative, "-")
        link(d, positive, fuse); link(d, fuse, connector, "+"); link(d, negative, connector, "-")
        return d
    }

    private fun troubleshooting(): FlowDocument {
        val d = FlowDocument("Troubleshooting")
        val start = el(d, ElementType.TERMINAL, 70f, 260f, "Problem reported")
        val reproduce = el(d, ElementType.PROCESS, 330f, 260f, "Reproduce")
        val logs = el(d, ElementType.PROCESS, 600f, 260f, "Check logs")
        val cause = el(d, ElementType.DECISION, 870f, 260f, "Cause known?")
        val fix = el(d, ElementType.PROCESS, 1150f, 120f, "Apply fix")
        val investigate = el(d, ElementType.PROCESS, 1150f, 420f, "Investigate")
        val verify = el(d, ElementType.DECISION, 1430f, 260f, "Resolved?")
        val end = el(d, ElementType.TERMINAL, 1710f, 120f, "Close")
        link(d, start, reproduce); link(d, reproduce, logs); link(d, logs, cause); link(d, cause, fix, "Yes"); link(d, cause, investigate, "No"); link(d, investigate, cause, "New evidence"); link(d, fix, verify); link(d, verify, end, "Yes"); link(d, verify, reproduce, "No")
        return d
    }

    private fun approval(): FlowDocument {
        val d = FlowDocument("Approval workflow")
        val request = el(d, ElementType.TERMINAL, 70f, 260f, "Request")
        val prepare = el(d, ElementType.PROCESS, 330f, 260f, "Prepare submission")
        val review = el(d, ElementType.PROCESS, 610f, 260f, "Review")
        val decision = el(d, ElementType.DECISION, 900f, 260f, "Approved?")
        val approved = el(d, ElementType.PROCESS, 1190f, 120f, "Process approval")
        val rejected = el(d, ElementType.PROCESS, 1190f, 420f, "Return for changes")
        val done = el(d, ElementType.TERMINAL, 1490f, 120f, "Complete")
        link(d, request, prepare); link(d, prepare, review); link(d, review, decision); link(d, decision, approved, "Yes"); link(d, decision, rejected, "No"); link(d, approved, done); link(d, rejected, prepare, "Revise")
        return d
    }

    private fun auth(): FlowDocument {
        val d = FlowDocument("Authentication")
        val start = el(d, ElementType.TERMINAL, 80f, 260f, "Sign in")
        val creds = el(d, ElementType.PROCESS, 350f, 260f, "Check credentials")
        val valid = el(d, ElementType.DECISION, 640f, 260f, "Valid?")
        val token = el(d, ElementType.PROCESS, 930f, 120f, "Issue token")
        val deny = el(d, ElementType.PROCESS, 930f, 400f, "Deny access")
        val session = el(d, ElementType.PROCESS, 1230f, 120f, "Create session")
        val end = el(d, ElementType.TERMINAL, 1510f, 120f, "Authenticated")
        link(d, start, creds); link(d, creds, valid); link(d, valid, token, "Yes"); link(d, valid, deny, "No"); link(d, token, session); link(d, session, end)
        return d
    }

    private fun deployment(): FlowDocument {
        val d = FlowDocument("Deployment")
        val source = el(d, ElementType.PROCESS, 80f, 260f, "Source")
        val build = el(d, ElementType.PROCESS, 350f, 260f, "Build")
        val test = el(d, ElementType.DECISION, 620f, 260f, "Tests pass?")
        val stage = el(d, ElementType.SERVER, 900f, 120f, "Staging")
        val prod = el(d, ElementType.SERVER, 1200f, 120f, "Production")
        val fix = el(d, ElementType.PROCESS, 900f, 420f, "Fix failure")
        link(d, source, build); link(d, build, test); link(d, test, stage, "Yes"); link(d, test, fix, "No"); link(d, stage, prod, "Release"); link(d, fix, source, "Retry")
        return d
    }

    private fun incident(): FlowDocument {
        val d = FlowDocument("Incident response")
        val alert = el(d, ElementType.TERMINAL, 80f, 260f, "Alert")
        val triage = el(d, ElementType.PROCESS, 350f, 260f, "Triage")
        val impact = el(d, ElementType.DECISION, 650f, 260f, "Customer impact?")
        val mitigate = el(d, ElementType.PROCESS, 930f, 120f, "Mitigate")
        val investigate = el(d, ElementType.PROCESS, 930f, 420f, "Investigate")
        val resolve = el(d, ElementType.PROCESS, 1220f, 260f, "Resolve")
        val review = el(d, ElementType.PROCESS, 1510f, 260f, "Post-incident review")
        link(d, alert, triage); link(d, triage, impact); link(d, impact, mitigate, "Yes"); link(d, impact, investigate, "No"); link(d, mitigate, resolve); link(d, investigate, resolve); link(d, resolve, review)
        return d
    }

    private fun security(): FlowDocument {
        val d = FlowDocument("Security review")
        val scope = el(d, ElementType.TERMINAL, 70f, 260f, "Review starts")
        val assets = el(d, ElementType.PROCESS, 340f, 260f, "Identify assets")
        val threats = el(d, ElementType.PROCESS, 620f, 260f, "Identify threats")
        val risk = el(d, ElementType.DECISION, 900f, 260f, "Risk acceptable?")
        val mitigate = el(d, ElementType.PROCESS, 1190f, 420f, "Mitigate risk")
        val approve = el(d, ElementType.PROCESS, 1190f, 100f, "Approve design")
        val verify = el(d, ElementType.PROCESS, 1490f, 100f, "Verify controls")
        val end = el(d, ElementType.TERMINAL, 1770f, 100f, "Review complete")
        link(d, scope, assets); link(d, assets, threats); link(d, threats, risk); link(d, risk, approve, "Yes"); link(d, risk, mitigate, "No"); link(d, mitigate, risk, "Reassess"); link(d, approve, verify); link(d, verify, end)
        return d
    }

    private fun stateMachine(): FlowDocument {
        val d = FlowDocument("State machine")
        val idle = el(d, ElementType.TERMINAL, 100f, 260f, "Idle")
        val active = el(d, ElementType.PROCESS, 390f, 260f, "Active")
        val paused = el(d, ElementType.PROCESS, 690f, 100f, "Paused")
        val failed = el(d, ElementType.PROCESS, 690f, 420f, "Failed")
        val complete = el(d, ElementType.TERMINAL, 1010f, 260f, "Complete")
        link(d, idle, active, "start"); link(d, active, paused, "pause"); link(d, paused, active, "resume"); link(d, active, failed, "error"); link(d, failed, active, "retry"); link(d, active, complete, "finish")
        return d
    }
}
