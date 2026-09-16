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
        "Battery circuit" to { batteryCircuit() },
        "Troubleshooting" to { troubleshooting() },
        "Approval workflow" to { approval() },
        "Authentication" to { auth() },
        "Deployment" to { deployment() },
        "Incident response" to { incident() },
        "Family tree" to { familyTree() },
        "Legal" to { legal() }
    )

    private fun el(d: FlowDocument, type: ElementType, x: Float, y: Float, label: String, notes: String = ""): FlowElement =
        FlowElement(type = type, x = x, y = y, label = label, notes = notes).also { d.elements += it }

    private fun link(d: FlowDocument, a: FlowElement, b: FlowElement, label: String = "", notes: String = "") {
        d.connections += FlowConnection(fromId = a.id, toId = b.id, label = label, notes = notes)
    }

    private fun basic(): FlowDocument {
        val d = FlowDocument("Basic flow")
        val s = el(d, ElementType.TERMINAL, 500f, 40f, "Start")
        val a = el(d, ElementType.PROCESS, 500f, 300f, "Prepare")
        val b = el(d, ElementType.PROCESS, 500f, 560f, "Process")
        val e = el(d, ElementType.TERMINAL, 500f, 820f, "End")
        link(d, s, a); link(d, a, b); link(d, b, e)
        return d
    }

    private fun decision(): FlowDocument {
        val d = FlowDocument("Decision flow")
        val s = el(d, ElementType.TERMINAL, 500f, 40f, "Start")
        val p = el(d, ElementType.PROCESS, 500f, 300f, "Check request")
        val q = el(d, ElementType.DECISION, 500f, 560f, "Valid?")
        val yes = el(d, ElementType.PROCESS, 250f, 840f, "Continue")
        val no = el(d, ElementType.PROCESS, 750f, 840f, "Fix problem")
        val e = el(d, ElementType.TERMINAL, 250f, 1100f, "Complete")
        link(d, s, p); link(d, p, q); link(d, q, yes, "Yes"); link(d, q, no, "No"); link(d, yes, e); link(d, no, p, "Retry")
        return d
    }

    private fun serverRoom(): FlowDocument {
        val d = FlowDocument("Server room")
        val internet = el(d, ElementType.SERVER, 500f, 40f, "Internet")
        val firewall = el(d, ElementType.SERVER, 500f, 300f, "Firewall")
        val switch = el(d, ElementType.SERVER, 500f, 560f, "Network switch")
        val app = el(d, ElementType.SERVER, 180f, 840f, "Application server")
        val db = el(d, ElementType.SERVER, 500f, 840f, "Database server")
        val backup = el(d, ElementType.SERVER, 820f, 840f, "Backup server")
        link(d, internet, firewall); link(d, firewall, switch)
        link(d, switch, app); link(d, switch, db); link(d, switch, backup)
        return d
    }

    private fun databaseArchitecture(): FlowDocument {
        val d = FlowDocument("Database architecture")
        val app = el(d, ElementType.PROCESS, 500f, 40f, "Application")
        val api = el(d, ElementType.SERVER, 500f, 300f, "Database API")
        val primary = el(d, ElementType.SERVER, 250f, 580f, "Primary database")
        val replica = el(d, ElementType.SERVER, 750f, 580f, "Read replica")
        val backup = el(d, ElementType.SERVER, 250f, 860f, "Backup")
        link(d, app, api); link(d, api, primary, "Read / write"); link(d, api, replica, "Read"); link(d, primary, replica, "Replicate"); link(d, primary, backup, "Backup")
        return d
    }

    private fun homeOfficeNetwork(): FlowDocument {
        val d = FlowDocument("Home / office network")
        val isp = el(d, ElementType.SERVER, 500f, 40f, "Internet provider")
        val router = el(d, ElementType.SERVER, 500f, 300f, "Router / gateway")
        val wifi = el(d, ElementType.SERVER, 250f, 580f, "Wi-Fi")
        val lan = el(d, ElementType.SERVER, 750f, 580f, "Ethernet switch")
        val pc = el(d, ElementType.PROCESS, 120f, 860f, "PCs / laptops")
        val phones = el(d, ElementType.PROCESS, 380f, 860f, "Phones / tablets")
        val printer = el(d, ElementType.PROCESS, 640f, 860f, "Printer")
        val nas = el(d, ElementType.SERVER, 900f, 860f, "NAS / storage")
        link(d, isp, router); link(d, router, wifi); link(d, router, lan)
        link(d, wifi, pc); link(d, wifi, phones); link(d, lan, pc); link(d, lan, printer); link(d, lan, nas)
        return d
    }

    private fun creative(): FlowDocument {
        val d = FlowDocument("Creative")
        val challenge = el(d, ElementType.TERMINAL, 500f, 40f, "Challenge")
        val brainstorm = el(d, ElementType.PROCESS, 500f, 300f, "Brainstorm")
        val a = el(d, ElementType.PROCESS, 140f, 580f, "Idea A")
        val b = el(d, ElementType.PROCESS, 380f, 580f, "Idea B")
        val c = el(d, ElementType.PROCESS, 620f, 580f, "Idea C")
        val ideaD = el(d, ElementType.PROCESS, 860f, 580f, "Idea D")
        val combine = el(d, ElementType.PROCESS, 500f, 860f, "Combine / expand")
        val refine = el(d, ElementType.DECISION, 500f, 1120f, "Promising?")
        val explore = el(d, ElementType.PROCESS, 240f, 1400f, "Explore further")
        val finalIdea = el(d, ElementType.TERMINAL, 760f, 1400f, "Refined idea")
        link(d, challenge, brainstorm)
        link(d, brainstorm, a); link(d, brainstorm, b); link(d, brainstorm, c); link(d, brainstorm, ideaD)
        link(d, a, combine); link(d, b, combine); link(d, c, combine); link(d, ideaD, combine)
        link(d, combine, refine); link(d, refine, finalIdea, "Yes"); link(d, refine, explore, "No"); link(d, explore, brainstorm, "New angle")
        return d
    }

    private fun logicalThought(): FlowDocument {
        val d = FlowDocument("Logical thought")
        val proposition = el(d, ElementType.TERMINAL, 500f, 40f, "Proposition")
        val evidence = el(d, ElementType.PROCESS, 180f, 330f, "Evidence")
        val assumptions = el(d, ElementType.PROCESS, 500f, 330f, "Assumptions")
        val counter = el(d, ElementType.PROCESS, 820f, 330f, "Counterexamples")
        val test = el(d, ElementType.PROCESS, 500f, 620f, "Test the proposition")
        val result = el(d, ElementType.DECISION, 500f, 900f, "Evidence supports it?")
        val supported = el(d, ElementType.TERMINAL, 180f, 1180f, "Supported")
        val uncertain = el(d, ElementType.TERMINAL, 500f, 1180f, "Uncertain")
        val rejected = el(d, ElementType.TERMINAL, 820f, 1180f, "Not supported")
        link(d, proposition, evidence); link(d, proposition, assumptions); link(d, proposition, counter)
        link(d, evidence, test); link(d, assumptions, test); link(d, counter, test); link(d, test, result)
        link(d, result, supported, "Yes"); link(d, result, uncertain, "Insufficient evidence"); link(d, result, rejected, "No")
        return d
    }

    private fun decisionMaking(): FlowDocument {
        val d = FlowDocument("Decision making")
        val goal = el(d, ElementType.TERMINAL, 500f, 40f, "Goal")
        val options = el(d, ElementType.PROCESS, 500f, 300f, "Identify options")
        val a = el(d, ElementType.PROCESS, 180f, 580f, "Option A")
        val b = el(d, ElementType.PROCESS, 500f, 580f, "Option B")
        val c = el(d, ElementType.PROCESS, 820f, 580f, "Option C")
        val criteria = el(d, ElementType.PROCESS, 500f, 860f, "Compare against criteria")
        val choose = el(d, ElementType.DECISION, 500f, 1140f, "Best option?")
        val act = el(d, ElementType.PROCESS, 760f, 1420f, "Take action")
        val reconsider = el(d, ElementType.PROCESS, 240f, 1420f, "Reconsider")
        link(d, goal, options); link(d, options, a); link(d, options, b); link(d, options, c)
        link(d, a, criteria); link(d, b, criteria); link(d, c, criteria); link(d, criteria, choose)
        link(d, choose, act, "Yes"); link(d, choose, reconsider, "No clear winner"); link(d, reconsider, options, "New option")
        return d
    }

    private fun problemSolving(): FlowDocument {
        val d = FlowDocument("Problem solving")
        val problem = el(d, ElementType.TERMINAL, 500f, 40f, "Problem")
        val observe = el(d, ElementType.PROCESS, 500f, 300f, "Observe / gather facts")
        val causes = el(d, ElementType.PROCESS, 500f, 580f, "Possible causes")
        val test = el(d, ElementType.DECISION, 500f, 860f, "Cause fits evidence?")
        val fix = el(d, ElementType.PROCESS, 760f, 1140f, "Apply solution")
        val investigate = el(d, ElementType.PROCESS, 240f, 1140f, "Investigate more")
        val verify = el(d, ElementType.DECISION, 760f, 1420f, "Solved?")
        val done = el(d, ElementType.TERMINAL, 760f, 1700f, "Done")
        link(d, problem, observe); link(d, observe, causes); link(d, causes, test)
        link(d, test, fix, "Yes"); link(d, test, investigate, "No"); link(d, investigate, causes, "New evidence")
        link(d, fix, verify); link(d, verify, done, "Yes"); link(d, verify, observe, "No")
        return d
    }

    private fun wiring(): FlowDocument {
        val d = FlowDocument("Wiring diagram")
        val source = el(d, ElementType.SERVER, 500f, 40f, "Power source", "Battery or DC supply")
        val fuse = el(d, ElementType.PROCESS, 500f, 300f, "Fuse")
        val switch = el(d, ElementType.PROCESS, 500f, 560f, "Switch")
        val load = el(d, ElementType.SERVER, 500f, 820f, "Load")
        val returnNode = el(d, ElementType.PROCESS, 500f, 1080f, "Return / negative")
        link(d, source, fuse, "+"); link(d, fuse, switch); link(d, switch, load, "Switched +"); link(d, load, returnNode, "-"); link(d, returnNode, source, "Return")
        return d
    }

    private fun batteryCircuit(): FlowDocument {
        val d = FlowDocument("Battery circuit")
        val battery = el(d, ElementType.SERVER, 500f, 40f, "Battery", "Example: car battery or 9V battery")
        val device = el(d, ElementType.SERVER, 500f, 420f, "Device", "Motor, fan, lamp, or other load")
        link(d, battery, device, "+ / -")
        link(d, device, battery, "Return")
        return d
    }

    private fun troubleshooting(): FlowDocument {
        val d = FlowDocument("Troubleshooting")
        val start = el(d, ElementType.TERMINAL, 500f, 40f, "Problem reported")
        val reproduce = el(d, ElementType.PROCESS, 500f, 300f, "Reproduce")
        val logs = el(d, ElementType.PROCESS, 500f, 580f, "Check logs")
        val cause = el(d, ElementType.DECISION, 500f, 860f, "Cause known?")
        val fix = el(d, ElementType.PROCESS, 760f, 1140f, "Apply fix")
        val investigate = el(d, ElementType.PROCESS, 240f, 1140f, "Investigate")
        val verify = el(d, ElementType.DECISION, 760f, 1420f, "Resolved?")
        val end = el(d, ElementType.TERMINAL, 760f, 1700f, "Close")
        link(d, start, reproduce); link(d, reproduce, logs); link(d, logs, cause); link(d, cause, fix, "Yes"); link(d, cause, investigate, "No"); link(d, investigate, cause, "New evidence"); link(d, fix, verify); link(d, verify, end, "Yes"); link(d, verify, reproduce, "No")
        return d
    }

    private fun approval(): FlowDocument {
        val d = FlowDocument("Approval workflow")
        val request = el(d, ElementType.TERMINAL, 500f, 40f, "Request")
        val prepare = el(d, ElementType.PROCESS, 500f, 300f, "Prepare submission")
        val review = el(d, ElementType.PROCESS, 500f, 580f, "Review")
        val decision = el(d, ElementType.DECISION, 500f, 860f, "Approved?")
        val approved = el(d, ElementType.PROCESS, 760f, 1140f, "Process approval")
        val rejected = el(d, ElementType.PROCESS, 240f, 1140f, "Return for changes")
        val done = el(d, ElementType.TERMINAL, 760f, 1420f, "Complete")
        link(d, request, prepare); link(d, prepare, review); link(d, review, decision); link(d, decision, approved, "Yes"); link(d, decision, rejected, "No"); link(d, approved, done); link(d, rejected, prepare, "Revise")
        return d
    }

    private fun auth(): FlowDocument {
        val d = FlowDocument("Authentication")
        val start = el(d, ElementType.TERMINAL, 500f, 40f, "Sign in")
        val creds = el(d, ElementType.PROCESS, 500f, 300f, "Check credentials")
        val valid = el(d, ElementType.DECISION, 500f, 580f, "Valid?")
        val token = el(d, ElementType.PROCESS, 760f, 860f, "Issue token")
        val deny = el(d, ElementType.PROCESS, 240f, 860f, "Deny access")
        val session = el(d, ElementType.PROCESS, 760f, 1140f, "Create session")
        val end = el(d, ElementType.TERMINAL, 760f, 1420f, "Authenticated")
        link(d, start, creds); link(d, creds, valid); link(d, valid, token, "Yes"); link(d, valid, deny, "No"); link(d, token, session); link(d, session, end)
        return d
    }

    private fun deployment(): FlowDocument {
        val d = FlowDocument("Deployment")
        val source = el(d, ElementType.PROCESS, 500f, 40f, "Source")
        val build = el(d, ElementType.PROCESS, 500f, 300f, "Build")
        val test = el(d, ElementType.DECISION, 500f, 580f, "Tests pass?")
        val stage = el(d, ElementType.SERVER, 760f, 860f, "Staging")
        val prod = el(d, ElementType.SERVER, 760f, 1140f, "Production")
        val fix = el(d, ElementType.PROCESS, 240f, 860f, "Fix failure")
        link(d, source, build); link(d, build, test); link(d, test, stage, "Yes"); link(d, test, fix, "No"); link(d, stage, prod, "Release"); link(d, fix, source, "Retry")
        return d
    }

    private fun incident(): FlowDocument {
        val d = FlowDocument("Incident response")
        val alert = el(d, ElementType.TERMINAL, 500f, 40f, "Alert")
        val triage = el(d, ElementType.PROCESS, 500f, 300f, "Triage")
        val impact = el(d, ElementType.DECISION, 500f, 580f, "Customer impact?")
        val mitigate = el(d, ElementType.PROCESS, 760f, 860f, "Mitigate")
        val investigate = el(d, ElementType.PROCESS, 240f, 860f, "Investigate")
        val resolve = el(d, ElementType.PROCESS, 500f, 1140f, "Resolve")
        val review = el(d, ElementType.PROCESS, 500f, 1420f, "Post-incident review")
        link(d, alert, triage); link(d, triage, impact); link(d, impact, mitigate, "Yes"); link(d, impact, investigate, "No"); link(d, mitigate, resolve); link(d, investigate, resolve); link(d, resolve, review)
        return d
    }

    private fun familyTree(): FlowDocument {
        val d = FlowDocument("Family tree")
        val grand1 = el(d, ElementType.PROCESS, 220f, 40f, "Grandparent A")
        val grand2 = el(d, ElementType.PROCESS, 780f, 40f, "Grandparent B")
        val parent = el(d, ElementType.PROCESS, 500f, 360f, "Parent")
        val child1 = el(d, ElementType.PROCESS, 300f, 680f, "Child A")
        val child2 = el(d, ElementType.PROCESS, 700f, 680f, "Child B")
        link(d, grand1, parent); link(d, grand2, parent); link(d, parent, child1); link(d, parent, child2)
        return d
    }

    private fun legal(): FlowDocument {
        val d = FlowDocument("Legal")
        val event = el(d, ElementType.TERMINAL, 500f, 40f, "Conduct / event / incident")
        val issue = el(d, ElementType.DECISION, 500f, 320f, "Potential legal issue?")
        val facts = el(d, ElementType.PROCESS, 760f, 620f, "Gather facts")
        val evidence = el(d, ElementType.PROCESS, 760f, 900f, "Preserve evidence")
        val law = el(d, ElementType.PROCESS, 760f, 1180f, "Identify rights, duties & obligations")
        val breach = el(d, ElementType.DECISION, 760f, 1460f, "Breach or dispute established?")
        val remedy = el(d, ElementType.PROCESS, 1020f, 1740f, "Response / remedy")
        val escalate = el(d, ElementType.PROCESS, 500f, 1740f, "Investigate further")
        val resolve = el(d, ElementType.TERMINAL, 1020f, 2020f, "Resolve / conclude")
        val noIssue = el(d, ElementType.TERMINAL, 240f, 620f, "No legal issue identified")
        link(d, event, issue)
        link(d, issue, facts, "Yes"); link(d, issue, noIssue, "No")
        link(d, facts, evidence); link(d, evidence, law); link(d, law, breach)
        link(d, breach, remedy, "Yes"); link(d, breach, escalate, "No / uncertain"); link(d, escalate, facts, "More facts")
        link(d, remedy, resolve)
        return d
    }

}
