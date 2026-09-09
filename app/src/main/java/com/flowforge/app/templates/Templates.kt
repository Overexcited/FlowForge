package com.flowforge.app.templates

import com.flowforge.app.model.*

object Templates {
    fun all(): List<Pair<String, () -> FlowDocument>> = listOf(
        "Basic flow" to { basic() },
        "Decision flow" to { decision() },
        "Swimlane" to { swimlane() },
        "Server architecture" to { servers() },
        "Client / API / DB" to { clientApiDb() },
        "Deployment" to { deployment() },
        "Incident response" to { incident() },
        "Authentication" to { auth() },
        "CI/CD pipeline" to { pipeline() },
        "Network topology" to { network() },
        "Data pipeline" to { dataPipeline() },
        "Troubleshooting" to { troubleshooting() },
        "Approval workflow" to { approval() },
        "User journey" to { journey() },
        "State machine" to { stateMachine() },
        "System overview" to { systemOverview() },
        "Process map" to { processMap() },
        "Database workflow" to { databaseWorkflow() },
        "Security review" to { security() },
        "Blank canvas" to { FlowDocument() }
    )

    private fun el(d: FlowDocument, type: ElementType, x: Float, y: Float, label: String, notes: String = ""): FlowElement {
        return FlowElement(type = type, x = x, y = y, label = label, notes = notes).also { d.elements += it }
    }

    private fun link(d: FlowDocument, a: FlowElement, b: FlowElement, label: String = "", notes: String = "") {
        d.connections += FlowConnection(fromId = a.id, toId = b.id, label = label, notes = notes)
    }

    private fun basic(): FlowDocument {
        val d = FlowDocument("Basic flow")
        val a = el(d, ElementType.TERMINAL, 120f, 260f, "Start")
        val b = el(d, ElementType.PROCESS, 380f, 260f, "Process")
        val c = el(d, ElementType.TERMINAL, 660f, 260f, "End")
        link(d, a, b); link(d, b, c); return d
    }

    private fun decision(): FlowDocument {
        val d = FlowDocument("Decision flow")
        val a = el(d, ElementType.TERMINAL, 100f, 250f, "Start")
        val b = el(d, ElementType.PROCESS, 350f, 250f, "Validate")
        val c = el(d, ElementType.DECISION, 650f, 250f, "Valid?")
        val y = el(d, ElementType.PROCESS, 900f, 130f, "Continue")
        val n = el(d, ElementType.PROCESS, 900f, 380f, "Fix error")
        link(d,a,b); link(d,b,c,""); link(d,c,y,"Yes"); link(d,c,n,"No"); return d
    }

    private fun servers(): FlowDocument {
        val d = FlowDocument("Server architecture")
        val client = el(d, ElementType.PROCESS, 100f, 260f, "Client", "Mobile/web client details")
        val api = el(d, ElementType.SERVER, 420f, 260f, "API Server", "Host, port, service owner, deployment notes")
        val db = el(d, ElementType.SERVER, 740f, 140f, "Database", "Engine, endpoint, backup policy")
        val cache = el(d, ElementType.SERVER, 740f, 380f, "Cache", "Redis/cache details")
        link(d,client,api,"HTTPS")
        link(d,api,db,"SQL")
        link(d,api,cache,"Cache")
        return d
    }

    private fun clientApiDb() = servers().also { it.title = "Client / API / DB" }

    private fun deployment(): FlowDocument {
        val d = FlowDocument("Deployment")
        val a=el(d,ElementType.PROCESS,80f,260f,"Source")
        val b=el(d,ElementType.PROCESS,330f,260f,"Build")
        val c=el(d,ElementType.PROCESS,580f,260f,"Test")
        val e=el(d,ElementType.SERVER,830f,260f,"Production")
        link(d,a,b);link(d,b,c);link(d,c,e);return d
    }

    private fun pipeline() = deployment().also { it.title="CI/CD pipeline" }
    private fun network() = servers().also { it.title="Network topology" }
    private fun dataPipeline() = deployment().also { it.title="Data pipeline" }
    private fun troubleshooting() = decision().also { it.title="Troubleshooting" }
    private fun approval() = decision().also { it.title="Approval workflow" }
    private fun journey() = basic().also { it.title="User journey" }
    private fun stateMachine() = decision().also { it.title="State machine" }
    private fun systemOverview() = servers().also { it.title="System overview" }
    private fun processMap() = basic().also { it.title="Process map" }
    private fun databaseWorkflow() = servers().also { it.title="Database workflow" }
    private fun security() = decision().also { it.title="Security review" }
    private fun incident() = decision().also { it.title="Incident response" }
    private fun auth() = decision().also { it.title="Authentication" }
    private fun swimlane() = basic().also { it.title="Swimlane" }
}
