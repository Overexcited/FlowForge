package com.flowforge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.*
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import android.graphics.drawable.GradientDrawable
import com.flowforge.app.mermaid.Mermaid
import com.flowforge.app.model.*
import com.flowforge.app.templates.Templates
import com.flowforge.app.ui.FlowCanvasView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Base64
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.io.File

class MainActivity : Activity() {
    private lateinit var canvas: FlowCanvasView
    private lateinit var prefs: SharedPreferences
    private lateinit var assets: AssetStore
    private var history = HistoryManager(2000)
    private var doc = FlowDocument()
    private var status: TextView? = null
    private var contextBar: LinearLayout? = null
    private var contextScroll: HorizontalScrollView? = null
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private var menuButton: Button? = null
    private var addButton: Button? = null
    private var pendingText = ""
    private var documentName = "Untitled"
    private var documentUri: Uri? = null
    // Only these two formats represent the editable document itself. Image/PDF exports never set this.
    private var documentFormat = "NONE"
    private var documentDirty = false
    private var pendingAfterSave:(()->Unit)? = null
    // The application chrome is permanently dark. The preference below controls only the canvas.
    private val uiDark = true

    companion object {
        private const val SAVE_JSON = 10; private const val SAVE_MERMAID = 11
        private const val OPEN_JSON = 12; private const val OPEN_MERMAID = 13
        private const val SAVE_PDF = 14; private const val SAVE_IMAGE = 15; private const val SAVE_PDF_DARK = 16; private const val SAVE_IMAGE_DARK = 17; private const val SAVE_JSON_AS = 18; private const val IMPORT_JSON = 19
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true)
        prefs = getSharedPreferences("flowforge", MODE_PRIVATE)
        assets = AssetStore(prefs)
        buildUi()
        applyPreferences()
        restoreSessionCache()
    }

    override fun onPause() {
        cacheSessionState()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        cacheSessionState()
        super.onSaveInstanceState(outState)
    }

    private fun cacheSessionState() {
        runCatching {
            val state = JSONObject()
            state.put("document", doc.toJson())
            state.put("documentName", documentName)
            state.put("documentUri", documentUri?.toString() ?: JSONObject.NULL)
            state.put("documentFormat", documentFormat)
            state.put("documentDirty", documentDirty)
            state.put("history", history.toJson())
            state.put("selectedElementId", canvas.selectedElementId ?: JSONObject.NULL)
            state.put("selectedConnectionId", canvas.selectedConnectionId ?: JSONObject.NULL)
            state.put("viewport", canvas.viewportStateJson())
            val tmp = File(filesDir, "flowforge-session-cache.tmp")
            val dst = File(filesDir, "flowforge-session-cache.json")
            tmp.writeText(state.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(dst)) {
                dst.delete()
                tmp.renameTo(dst)
            }
        }
    }

    private fun restoreSessionCache() {
        val cache = File(filesDir, "flowforge-session-cache.json")
        runCatching {
            if (!cache.exists()) return
            val state = JSONObject(cache.readText(Charsets.UTF_8))
            val restored = FlowDocument.fromJson(state.getString("document"))
            doc = restored
            canvas.document = doc
            documentUri = state.optString("documentUri", "").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse)
            // An unsaved session is never turned into a pseudo-file merely because
            // the recovery cache happens to contain a temporary/import name.
            documentName = if (documentUri != null) {
                displayDocumentName(state.optString("documentName", "Untitled"))
            } else {
                "Untitled"
            }
            documentFormat = state.optString("documentFormat", if (documentUri != null) "JSON" else "NONE").uppercase(Locale.US)
            if (documentUri == null) documentFormat = "NONE"
            documentDirty = state.optBoolean("documentDirty", false)
            history = HistoryManager.fromJson(state.optString("history", ""), 2000)
            canvas.restoreViewportState(state.optString("viewport", ""))
            canvas.selectedElementId = state.optString("selectedElementId", "").takeIf { it.isNotBlank() && it != "null" }
            canvas.selectedConnectionId = state.optString("selectedConnectionId", "").takeIf { it.isNotBlank() && it != "null" }
            canvas.finishConnectionMode()
            canvas.finishCustomShapeMode()
            canvas.invalidate()
            updateUi()
        }
    }

    private fun buildUi() {
        // Initialize the canvas before constructing any UI that reads its settings.
        canvas = FlowCanvasView(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(if (uiDark) 0xff0f172a.toInt() else Color.WHITE)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            v.setPadding(0, top, 0, bottom)
            insets
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(if (uiDark) 0xff020617.toInt() else 0xff0f172a.toInt())
        }
        top.addView(iconButton("☰", "Menu") { mainMenu() }.also { menuButton = it })
        top.addView(TextView(this).apply {
            text = "FlowForge"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(iconButton("↶", "Undo") { undo() }.also { undoButton = it })
        top.addView(iconButton("↷", "Redo") { redo() }.also { redoButton = it })
        top.addView(iconButton("＋", "Add") { addMenu() }.also { addButton = it })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (uiDark) 0xff0f172a.toInt() else Color.WHITE)
        }
        content.addView(top, LinearLayout.LayoutParams(-1, dp(62)))

        status = TextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            setTextColor(Color.WHITE); setBackgroundColor(if (uiDark) 0xff273449.toInt() else 0xffe2e8f0.toInt())
        }
        content.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        contextBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(if (uiDark) 0xff111827.toInt() else 0xfff8fafc.toInt()); visibility = View.GONE
        }
        contextScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(contextBar)
        }
        // The contextual toolbar is added as an overlay below, so it never changes the canvas layout.
        

        canvas.onSelectionChanged = { updateUi() }
        canvas.onDoubleTapElement = { showElementEditor(it) }
        canvas.onNotesTap = { showNotes(it) }
        canvas.onConnectionRequested = { from, to, fromSide, toSide, route -> createConnection(from, to, fromSide, toSide, route) }
        canvas.onConnectionCancelled = { updateUi() }
        canvas.onCustomShapeFinished = { points -> applyCustomShape(points) }
        canvas.onMoveFinished = { e, oldX, oldY ->
            val before=doc.deepCopy(); before.elements.firstOrNull{it.id==e.id}?.apply{x=oldX;y=oldY}
            history.record(before,doc.deepCopy()); documentDirty=true; updateUi()
        }
        canvas.onResizeFinished = { e, oldX, oldY, oldW, oldH ->
            val before=doc.deepCopy(); before.elements.firstOrNull{it.id==e.id}?.apply{x=oldX;y=oldY;width=oldW;height=oldH}
            history.record(before,doc.deepCopy()); documentDirty=true; updateUi()
        }
        content.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(contextScroll, FrameLayout.LayoutParams(-1, dp(48)).apply {
            gravity = Gravity.TOP
            topMargin = dp(90) // 62dp top bar + 28dp status bar
        })
        setContentView(root)
        root.requestApplyInsets()
        updateUi()
    }

    private fun iconButton(symbol:String, description:String, action:()->Unit) = Button(this).apply {
        text=symbol; contentDescription=description; textSize=21f; minHeight=0; minimumHeight=0
        setPadding(0,0,0,0); isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener{action()}; layoutParams=LinearLayout.LayoutParams(dp(44),dp(52))
    }

    private fun smallButton(label:String, action:()->Unit) = TextView(this).apply {
        text=label; textSize=12f; includeFontPadding=false; gravity=Gravity.CENTER; isSingleLine=true
        // Size the button from its text instead of Android Button's built-in minimum width.
        // 32dp height gives roughly the same visual padding above/below as the 9dp
        // left/right padding used here.
        setPadding(dp(9),0,dp(9),0)
        val dark=uiDark
        setTextColor(if(dark) Color.WHITE else 0xff172033.toInt())
        background=GradientDrawable().apply{
            cornerRadius=dp(7).toFloat()
            setColor(if(dark) 0xff1e293b.toInt() else 0xffe2e8f0.toInt())
            setStroke(dp(1),if(dark) 0xff475569.toInt() else 0xffcbd5e1.toInt())
        }
        setOnClickListener{action()}
        layoutParams=LinearLayout.LayoutParams(WRAP_CONTENT,dp(32)).apply{setMargins(dp(4),dp(1),dp(4),dp(1))}
        // Keep each button at its natural text width. Never squeeze or wrap labels.
        // The toolbar's spacer fills any remaining screen width, while the horizontal
        // margins provide a comfortable gap between the buttons.
    }

    private fun updateUi() {
        undoButton?.isEnabled=history.canUndo(); undoButton?.alpha=if(history.canUndo())1f else 0.45f
        redoButton?.isEnabled=history.canRedo(); redoButton?.alpha=if(history.canRedo())1f else 0.45f
        contextBar?.setBackgroundColor(if(uiDark)0xff111827.toInt() else 0xfff8fafc.toInt())
        contextScroll?.setBackgroundColor(if(uiDark)0xff111827.toInt() else 0xfff8fafc.toInt())
        val statusView = status
        if (canvas.connectionMode) {
            statusView?.apply {
                setBackgroundColor(if(uiDark) 0xff123524.toInt() else 0xffdcfce7.toInt())
                setTextColor(if(uiDark) 0xff86efac.toInt() else 0xff166534.toInt())
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                text = "Connect Mode: Tap any two points to connect them"
            }
        } else {
            statusView?.apply {
                setBackgroundColor(if(uiDark)0xff273449.toInt() else 0xffe2e8f0.toInt())
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                text = "${if(documentUri != null) documentName else "Untitled"}${if(documentDirty)" • Unsaved" else ""}  •  ${doc.elements.size} blocks  •  ${doc.connections.size} connections"
            }
        }
        val bar=contextBar ?: return
        val scroll=contextScroll ?: return
        bar.removeAllViews()
        val e=canvas.selectedElementId?.let{id->doc.elements.firstOrNull{it.id==id}}
        val c=canvas.selectedConnectionId?.let{id->doc.connections.firstOrNull{it.id==id}}
        if(canvas.customShapeMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Draw Custom Shape";textSize=12f;setTextColor(if(uiDark)Color.WHITE else 0xff172033.toInt());setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            bar.addView(smallButton("✓"){canvas.commitCustomShape()})
            bar.addView(smallButton("Cancel"){canvas.cancelCustomShapeMode()})
        } else if(e!=null && !canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(smallButton("Edit"){showElementEditor(e)})
            bar.addView(smallButton("Connect"){canvas.beginConnectionMode()})
            bar.addView(smallButton("Clone"){cloneElement(e)})
            bar.addView(smallButton("Reset"){resetElement(e)})
            bar.addView(smallButton("Delete"){deleteSelected()})
            bar.addView(smallButton("Save Block"){saveAsset(e)})
            bar.addView(Space(this), LinearLayout.LayoutParams(0,1,1f))
        } else if(c!=null && !canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(smallButton("Reverse"){reverseConnection(c)})
            bar.addView(smallButton("Color"){showConnectionColorPicker(c)})
            bar.addView(smallButton("Style: ${lineStyleLabel(c.lineStyle)}"){cycleConnectionLineStyle(c)})
            bar.addView(smallButton("Arrows: ${arrowLabel(c.arrowType)}"){cycleConnectionArrow(c)})
            bar.addView(smallButton("Edit"){showConnectionEditor(c)})
            bar.addView(smallButton("Delete"){deleteSelected()})
            bar.addView(Space(this), LinearLayout.LayoutParams(0,1,1f))
        } else if(canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(Space(this), LinearLayout.LayoutParams(0,1,1f))
            bar.addView(smallButton("Cancel"){canvas.cancelConnectionMode()})
        } else {
            bar.visibility=View.GONE; scroll.visibility=View.GONE
        }
    }

    private fun lineStyleLabel(style:LineStyle)=when(style){LineStyle.SOLID->"Solid";LineStyle.DASHED->"Dashed";LineStyle.DOTTED->"Dotted"}
    private fun arrowLabel(type:ArrowType)=when(type){ArrowType.NONE->"None";ArrowType.END->"End";ArrowType.BOTH->"Both";ArrowType.CIRCLE->"Circle";ArrowType.DIAMOND->"Diamond";ArrowType.REPEATED->"Flow"}
    private fun cycleConnectionLineStyle(c:FlowConnection){
        val before=doc.deepCopy(); c.lineStyle=when(c.lineStyle){LineStyle.SOLID->LineStyle.DASHED;LineStyle.DASHED->LineStyle.DOTTED;LineStyle.DOTTED->LineStyle.SOLID}; history.record(before,doc.deepCopy());canvas.invalidate();updateUi()
    }
    private fun cycleConnectionArrow(c:FlowConnection){
        val before=doc.deepCopy(); c.arrowType=when(c.arrowType){ArrowType.NONE->ArrowType.END;ArrowType.END->ArrowType.REPEATED;ArrowType.REPEATED->ArrowType.BOTH;ArrowType.BOTH->ArrowType.NONE;ArrowType.CIRCLE->ArrowType.DIAMOND;ArrowType.DIAMOND->ArrowType.NONE}; history.record(before,doc.deepCopy());canvas.invalidate();updateUi()
    }
    private fun reverseConnection(c:FlowConnection){
        val before=doc.deepCopy(); val from=c.fromId;c.fromId=c.toId;c.toId=from;val side=c.fromSide;c.fromSide=c.toSide;c.toSide=side;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()
    }

    private fun mainMenu(){
        val anchor=menuButton ?: return
        showStyledPopup("FlowForge", listOf("Save...","Open","Recents","Fit diagram to screen","Settings"), anchor, emptySet()){which->
            when(which){
                0->saveAs()
                1->openDocument()
                2->recents()
                3->fitDiagramToScreen()
                4->settings()
            }
        }
    }

    private fun addMenu(){
        val anchor=addButton ?: return
        showStyledPopup("Add", listOf("New Block","Saved Block","Blank Canvas","From Template"), anchor, setOf(2)){choice->
            when(choice){
                0->addElement()
                1->assetPicker()
                2->newDocument()
                3->templates()
            }
        }
    }

    private fun showStyledPopup(title:String, items:List<String>, anchor:View?=null, separatorBefore:Set<Int> = emptySet(), onChoice:(Int)->Unit){
        val dark=uiDark
        lateinit var popup: PopupWindow
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(6),dp(6),dp(6),dp(6))
            setBackgroundColor(Color.TRANSPARENT)
        }
        outer.addView(TextView(this).apply{
            text=title; textSize=14f; setTypeface(null,Typeface.BOLD);
            // Keep the title row's exact size/padding, but hide the title text.
            setTextColor(Color.TRANSPARENT)
            gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
            setPadding(dp(10),dp(6),dp(10),dp(8))
        },LinearLayout.LayoutParams(dp(280),dp(34)))
        val listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        items.forEachIndexed{index,label->
            if(index in separatorBefore) listBox.addView(View(this).apply{
                setBackgroundColor(if(dark)0xff475569.toInt() else 0xffcbd5e1.toInt())
            },LinearLayout.LayoutParams(dp(250),dp(1)).apply{setMargins(dp(15),dp(5),dp(15),dp(5))})
            listBox.addView(TextView(this).apply{
                text=label; textSize=16f; gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
                setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())
                setPadding(dp(14),0,dp(14),0)
                background=GradientDrawable().apply{
                    cornerRadius=dp(9).toFloat()
                    setColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
                    setStroke(dp(1),if(dark)0xff334155.toInt() else 0xffe2e8f0.toInt())
                }
                setOnClickListener{popup.dismiss();onChoice(index)}
            },LinearLayout.LayoutParams(dp(280),dp(48)).apply{setMargins(0,dp(2),0,dp(2))})
        }
        val scroll=ScrollView(this).apply{
            isFillViewport=true; isVerticalScrollBarEnabled=false; addView(listBox)
        }
        outer.addView(scroll,LinearLayout.LayoutParams(WRAP_CONTENT,dp(8+items.size*52+separatorBefore.size*11).coerceAtMost(dp(500).toInt())))
        popup=PopupWindow(outer,WRAP_CONTENT,WRAP_CONTENT,true).apply{
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(12).toFloat(); isOutsideTouchable=true
        }
        fun installBlockOcclusion() {
            outer.post {
                val popupLoc = IntArray(2)
                val canvasLoc = IntArray(2)
                outer.getLocationOnScreen(popupLoc)
                canvas.getLocationOnScreen(canvasLoc)
                canvas.setPopupBlockOcclusion(RectF(
                    (popupLoc[0] - canvasLoc[0]).toFloat(),
                    (popupLoc[1] - canvasLoc[1]).toFloat(),
                    (popupLoc[0] - canvasLoc[0] + outer.width).toFloat(),
                    (popupLoc[1] - canvasLoc[1] + outer.height).toFloat()
                ))
            }
        }
        popup.setOnDismissListener { canvas.setPopupBlockOcclusion(null) }
        if(anchor!=null) {
            popup.showAsDropDown(anchor,-dp(2),dp(2))
            installBlockOcclusion()
        } else {
            popup.showAtLocation(window.decorView,Gravity.CENTER,0,0)
            installBlockOcclusion()
        }
    }

    private fun showCompactPopup(anchor:View, title:String, items:List<String>, onChoice:(Int)->Unit){
        showStyledPopup(title, items, anchor, emptySet(), onChoice)
    }

    private fun showCenteredCompactPopup(title:String, items:List<String>, onChoice:(Int)->Unit){
        showStyledPopup(title, items, null, emptySet(), onChoice)
    }

    private fun replaceDocument(newDoc: FlowDocument, record:Boolean=true) { if(record)history.record(doc.deepCopy(),newDoc.deepCopy());doc=newDoc;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;updateUi() }
    private fun newDocument(){
        if(documentDirty){
            dialogBuilder().setTitle("Save changes?")
                .setMessage("\"$documentName\" has unsaved changes. Save before creating a new canvas?")
                .setNegativeButton("Cancel",null)
                .setNeutralButton("Don't Save"){_,_->startNewDocument()}
                .setPositiveButton("Save"){_,_->saveCurrentThen{startNewDocument()}}
                .show()
        } else startNewDocument()
    }

    private fun startNewDocument(){
        history=HistoryManager(2000)
        doc=FlowDocument()
        canvas.document=doc
        canvas.selectedElementId=null
        canvas.selectedConnectionId=null
        documentName="Untitled"
        documentUri=null
        documentDirty=false
        canvas.resetViewport()
        canvas.invalidate()
        updateUi()
    }

    private fun addElement(){
        val e=FlowElement(type=ElementType.PROCESS,x=260f+doc.elements.size*35f,y=220f+doc.elements.size*25f,width=270f,height=135f,label="")
        // New elements start as the standard rounded block and can be reshaped later.
        val before=doc.deepCopy(); doc.elements+=e
        canvas.selectedElementId=e.id; canvas.selectedConnectionId=null
        history.record(before,doc.deepCopy()); documentDirty=true
        canvas.invalidate(); updateUi()
    }

    private fun createConnection(fromId:String,toId:String,fromSide:ConnectionSide=ConnectionSide.AUTO,toSide:ConnectionSide=ConnectionSide.AUTO,route:List<PointF> = emptyList()){
        if(fromId==toId)return
        val before=doc.deepCopy()
        val c=FlowConnection(
            fromId=fromId,
            toId=toId,
            fromSide=fromSide,
            toSide=toSide,
            routePoints=mutableListOf()
        )
        doc.connections+=c
        canvas.selectedConnectionId=c.id;canvas.selectedElementId=null;history.record(before,doc.deepCopy());canvas.cancelConnectionMode();canvas.invalidate();updateUi()
    }

    private fun deleteSelected(){
        val before=doc.deepCopy()
        val eid=canvas.selectedElementId
        val cid=canvas.selectedConnectionId
        if(eid!=null){
            doc.elements.removeAll{it.id==eid}
            doc.connections.removeAll{it.fromId==eid||it.toId==eid}
        }
        if(cid!=null) doc.connections.removeAll{it.id==cid}
        if(before.toJson()!=doc.toJson()) history.record(before,doc.deepCopy())
        canvas.selectedElementId=null
        canvas.selectedConnectionId=null
        canvas.invalidate()
        updateUi()
    }

    private fun applyCustomShape(points: List<PointF>){
        val id=canvas.customShapeTargetId ?: return
        val e=doc.elements.firstOrNull{it.id==id} ?: return
        if(points.size < 8){ toast("Draw a larger shape first"); updateUi(); return }
        val before=doc.deepCopy()
        val minX=points.minOf{it.x}; val maxX=points.maxOf{it.x}; val minY=points.minOf{it.y}; val maxY=points.maxOf{it.y}
        val w=max(40f,maxX-minX); val h=max(40f,maxY-minY)
        e.x=minX; e.y=minY; e.width=w; e.height=h
        e.customPoints=points.map{ConnectionPoint(((it.x-minX)/w).coerceIn(0f,1f),((it.y-minY)/h).coerceIn(0f,1f))}.toMutableList()
        history.record(before,doc.deepCopy()); documentDirty=true; canvas.finishCustomShapeMode(); canvas.invalidate(); updateUi()
    }

    private fun showElementEditor(e:FlowElement){
        val dark=uiDark
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(6),dp(22),dp(4));background=GradientDrawable().apply{cornerRadius=dp(18).toFloat();setColor(if(dark)0xff0f172a.toInt() else Color.WHITE)}}
        val fieldText=if(dark)Color.WHITE else 0xff172033.toInt(); val fieldHint=if(dark)0xff94a3b8.toInt() else 0xff64748b.toInt()
        fun edit(initial:String,hintText:String,minLines:Int=1,multiline:Boolean=false)=EditText(this).apply{
            setText(initial)
            hint=hintText
            if(minLines>1)this.minLines=minLines
            if(multiline){
                setSingleLine(false)
                maxLines=8
                gravity=Gravity.TOP or Gravity.START
                setHorizontallyScrolling(false)
                inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            setTextColor(fieldText)
            setHintTextColor(fieldHint)
            if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=android.content.res.ColorStateList.valueOf(if(dark)0xff64748b.toInt() else 0xff94a3b8.toInt())
        }
        val label=edit(e.label,"",3,true)
        val notes=edit(e.notes,"Metadata / notes",3,true)
        val shapeEntries=listOf(
            ShapeType.RECTANGLE, ShapeType.ROUNDED, ShapeType.EXTRA_ROUNDED,
            ShapeType.OVAL, ShapeType.TRIANGLE, ShapeType.STAR, ShapeType.CLOUD,
            ShapeType.TRAPEZOID_TOP_SHORT, ShapeType.TRAPEZOID_BOTTOM_SHORT,
            ShapeType.CYLINDER, ShapeType.DIAMOND, ShapeType.HEXAGON
        )
        val shapeLabels=shapeEntries.map{shapeName(it)} + "Custom"
        val thicknesses=arrayOf(LineThickness.DEFAULT,LineThickness.MEDIUM,LineThickness.LARGE)
        val thicknessSpinner=thicknessSpinner(e.outlineThickness)
        val outlineStyleSpinner=outlineStyleSpinner(e.outlineLineStyle)
        val fillSpinner=fillColorSpinner(e.fillColor)
        val outlineSpinner=outlineColorSpinner(e.outlineColor)
        val labelColorSpinner=labelColorSpinner(e.labelColor)
        val labelTextSizeSpinner=textSizeSpinner(e.labelTextSize)
        val labelFontSpinner=fontSpinner(e.labelFont)
        val labelStyleChecks=textStyleChecks(e.labelBold,e.labelItalic,e.labelUnderline,dark)
        fun sentence(value:String)=value.lowercase().replaceFirstChar{it.uppercase()}
        val spinner=Spinner(this).apply{
            adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,shapeLabels){
                override fun isEnabled(position:Int)=true
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(8),dp(10),dp(8))}}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),dp(10),dp(14),dp(10));alpha=1f}}}
            };setSelection(if(e.customPoints.size>=3) shapeEntries.size else shapeEntries.indexOf(e.shape).coerceAtLeast(0));setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
        box.addView(editorLabel("Label"));box.addView(label)
        box.addView(editorLabel("Label Color"));box.addView(labelColorSpinner)
        box.addView(editorLabel("Text size"));box.addView(labelTextSizeSpinner)
        box.addView(editorLabel("Font"));box.addView(labelFontSpinner)
        box.addView(editorLabel("Text style"));box.addView(labelStyleChecks)
        box.addView(editorLabel("Shape"));box.addView(spinner)
        box.addView(editorLabel("Outline thickness"));box.addView(thicknessSpinner)
        box.addView(editorLabel("Outline type"));box.addView(outlineStyleSpinner)
        box.addView(editorLabel("Outline Color"));box.addView(outlineSpinner)
        box.addView(editorLabel("Fill colour"));box.addView(fillSpinner)
        box.addView(editorLabel("Notes"));box.addView(notes)
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(box)}
        val dialog=dialogBuilder().setTitle("Edit Block").setView(scroll).setPositiveButton("Save"){_,_->
            val before=doc.deepCopy();e.label=label.text.toString();e.notes=notes.text.toString();e.outlineThickness=thicknesses[thicknessSpinner.selectedItemPosition];e.outlineLineStyle=LineStyle.values()[outlineStyleSpinner.selectedItemPosition]
            e.outlineColor=outlineColors().values.elementAt(outlineSpinner.selectedItemPosition)
            e.labelColor=labelColors().values.elementAt(labelColorSpinner.selectedItemPosition)
            e.labelTextSize=TextSize.values()[labelTextSizeSpinner.selectedItemPosition]
            e.labelFont=TextFont.values()[labelFontSpinner.selectedItemPosition]
            e.labelBold=labelStyleChecks.getChildAt(0).let{(it as CheckBox).isChecked}
            e.labelItalic=labelStyleChecks.getChildAt(1).let{(it as CheckBox).isChecked}
            e.labelUnderline=labelStyleChecks.getChildAt(2).let{(it as CheckBox).isChecked}
            e.fillColor=fillColors().values.elementAt(fillSpinner.selectedItemPosition)
            if(spinner.selectedItemPosition < shapeEntries.size){
                e.shape=shapeEntries[spinner.selectedItemPosition]; e.customPoints.clear()
                history.record(before,doc.deepCopy());documentDirty=true;canvas.invalidate();updateUi()
            } else {
                if(before.toJson()!=doc.toJson()){ history.record(before,doc.deepCopy()); documentDirty=true }
                canvas.beginCustomShapeMode(e.id)
            }
        }.setNeutralButton("Reset default"){_,_->resetElement(e)}.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{val textColor=if(dark)Color.WHITE else 0xff172033.toInt();dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(textColor);dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(textColor);dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(textColor);dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if(dark)0xff0f172a.toInt() else Color.WHITE))}
        dialog.show()
    }
    private fun resetElement(e:FlowElement){val before=doc.deepCopy();e.shape=FlowElement.defaultShape(e.type);e.width=270f;e.height=135f;e.outlineThickness=LineThickness.DEFAULT;e.outlineLineStyle=LineStyle.SOLID;e.outlineColor=null;e.fillColor=null;e.labelColor=null;e.labelTextSize=TextSize.MEDIUM;e.labelBold=false;e.labelItalic=false;e.labelUnderline=false;e.labelFont=TextFont.SANS;e.customPoints.clear();history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}

    private fun thicknessSpinner(current:LineThickness):Spinner {
        val names=listOf("Default","Medium","Large"); val dark=uiDark
        return Spinner(this).apply{
            adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,names){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(8),dp(10),dp(8))}}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),dp(10),dp(14),dp(10))}}}
            }
            setSelection(current.ordinal);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun outlineStyleSpinner(current:LineStyle):Spinner {
        val names=listOf("Solid","Dashed","Dotted"); val dark=uiDark
        return Spinner(this).apply{
            adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,names){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(8),dp(10),dp(8))}}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),dp(10),dp(14),dp(10))}}}
            }
            setSelection(current.ordinal);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun textSizeSpinner(current: TextSize): Spinner {
        val names=listOf("Small","Normal","Medium","Large","Extra large","Huge")
        val dark=uiDark
        return Spinner(this).apply{
            adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,names){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(8),dp(10),dp(8))}}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),dp(10),dp(14),dp(10))}}}
            }
            setSelection(current.ordinal);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun fontSpinner(current: TextFont): Spinner {
        val names=listOf("Sans Serif","Serif","Monospace","Sans Serif Condensed","Sans Serif Light")
        val dark=uiDark
        return Spinner(this).apply{
            adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,names){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(8),dp(10),dp(8))}}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.apply{setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),dp(10),dp(14),dp(10))}}}
            }
            setSelection(current.ordinal);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun textStyleChecks(bold:Boolean,italic:Boolean,underline:Boolean,dark:Boolean):LinearLayout{
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        fun check(label:String,checked:Boolean)=CheckBox(this).apply{
            text=label;isChecked=checked;textSize=13f;includeFontPadding=false;setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(0,0,dp(8),0)
        }
        row.addView(check("Bold",bold));row.addView(check("Italic",italic));row.addView(check("Underline",underline));return row
    }

    private fun colorSpinnerAdapter(names: List<String>, colors: List<Int?>, dark: Boolean): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            private fun row(position: Int, dropdown: Boolean): View {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(if (dark) 0xff1e293b.toInt() else Color.WHITE)
                    setPadding(dp(if (dropdown) 14 else 10), dp(if (dropdown) 8 else 6), dp(if (dropdown) 14 else 10), dp(if (dropdown) 8 else 6))
                }
                val label = TextView(this@MainActivity).apply {
                    text = names[position]
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(if (dark) Color.WHITE else 0xff172033.toInt())
                    includeFontPadding = false
                    setSingleLine(true)
                }
                row.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val swatch = View(this@MainActivity).apply {
                    val color = colors[position]
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color ?: Color.TRANSPARENT)
                        setStroke(dp(1), if (color == null) (if (dark) 0xff94a3b8.toInt() else 0xff64748b.toInt()) else color)
                    }
                }
                val size = dp(18)
                val lp = LinearLayout.LayoutParams(size, size)
                lp.gravity = Gravity.CENTER_VERTICAL
                lp.marginStart = dp(8)
                row.addView(swatch, lp)
                return row
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, true)
        }

    private fun fillColors():LinkedHashMap<String,Int?> = linkedMapOf(
        "No fill" to null, "Black" to Color.BLACK, "White" to Color.WHITE,
        "Red" to 0xffdc2626.toInt(), "Orange" to 0xffea580c.toInt(), "Yellow" to 0xffca8a04.toInt(),
        "Green" to 0xff16a34a.toInt(), "Blue" to 0xff2563eb.toInt(), "Purple" to 0xff7c3aed.toInt(),
        "Pink" to 0xffdb2777.toInt(), "Teal" to 0xff0f766e.toInt(), "Gray" to 0xff64748b.toInt()
    )

    private fun fillColorSpinner(current:Int?):Spinner {
        val colors=fillColors(); val names=colors.keys.toList(); val dark=uiDark
        return Spinner(this).apply{
            adapter=colorSpinnerAdapter(names,colors.values.toList(),dark)
            val idx=colors.values.indexOf(current);setSelection(if(idx>=0)idx else 0);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun outlineColors(): LinkedHashMap<String, Int?> = linkedMapOf(
        "Default" to null, "Black" to Color.BLACK, "White" to Color.WHITE,
        "Red" to 0xffdc2626.toInt(), "Orange" to 0xffea580c.toInt(), "Yellow" to 0xffca8a04.toInt(),
        "Green" to 0xff16a34a.toInt(), "Blue" to 0xff2563eb.toInt(), "Purple" to 0xff7c3aed.toInt(),
        "Pink" to 0xffdb2777.toInt(), "Teal" to 0xff0f766e.toInt(), "Gray" to 0xff64748b.toInt()
    )

    private fun outlineColorSpinner(current:Int?): Spinner {
        val colors=outlineColors(); val names=colors.keys.toList(); val dark=uiDark
        val displayColors=colors.values.map{it?:if(dark)0xff94a3b8.toInt() else 0xff334155.toInt()}
        return Spinner(this).apply{
            adapter=colorSpinnerAdapter(names,displayColors,dark)
            val idx=colors.values.indexOf(current);setSelection(if(idx>=0)idx else 0);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun labelColors(): LinkedHashMap<String, Int?> = linkedMapOf(
        "Default" to null, "Black" to Color.BLACK, "White" to Color.WHITE,
        "Red" to 0xffdc2626.toInt(), "Orange" to 0xffea580c.toInt(), "Yellow" to 0xffca8a04.toInt(),
        "Green" to 0xff16a34a.toInt(), "Blue" to 0xff2563eb.toInt(), "Purple" to 0xff7c3aed.toInt(),
        "Pink" to 0xffdb2777.toInt(), "Teal" to 0xff0f766e.toInt(), "Gray" to 0xff64748b.toInt()
    )

    private fun labelColorSpinner(current:Int?): Spinner {
        val colors=labelColors(); val names=colors.keys.toList(); val dark=uiDark
        val displayColors=colors.values.map{it?:if(dark)Color.WHITE else 0xff172033.toInt()}
        return Spinner(this).apply{
            adapter=colorSpinnerAdapter(names,displayColors,dark)
            val idx=colors.values.indexOf(current);setSelection(if(idx>=0)idx else 0);setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun connectionColors(): LinkedHashMap<String, Int> = linkedMapOf(
        "Default" to 0xff475569.toInt(), "Black" to Color.BLACK, "White" to Color.WHITE,
        "Red" to 0xffdc2626.toInt(), "Orange" to 0xffea580c.toInt(), "Yellow" to 0xffca8a04.toInt(),
        "Green" to 0xff16a34a.toInt(), "Blue" to 0xff2563eb.toInt(), "Purple" to 0xff7c3aed.toInt(),
        "Pink" to 0xffdb2777.toInt(), "Teal" to 0xff0f766e.toInt(), "Gray" to 0xff64748b.toInt()
    )

    private fun connectionColorSpinner(current:Int): Spinner {
        val colors=connectionColors()
        val names=colors.keys.toList()
        val dark=uiDark
        return Spinner(this).apply{
            adapter=colorSpinnerAdapter(names,colors.values.toList(),dark)
            setSelection(colors.values.indexOf(current).takeIf{it>=0} ?: 0)
            setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
    }

    private fun editorLabel(text:String)=TextView(this).apply{
        this.text=text
        textSize=13f
        setTextColor(if(uiDark)Color.WHITE else 0xff172033.toInt())
        setPadding(0,dp(10),0,dp(3))
    }

    private fun showConnectionEditor(c:FlowConnection){
        val dark=uiDark
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(6),dp(22),dp(4))
            background=GradientDrawable().apply{cornerRadius=dp(18).toFloat();setColor(if(dark)0xff0f172a.toInt() else Color.WHITE)}
        }
        val fieldText=if(dark)Color.WHITE else 0xff172033.toInt()
        val fieldHint=if(dark)0xff94a3b8.toInt() else 0xff64748b.toInt()
        fun edit(initial:String,hintText:String,minLines:Int=1,multiline:Boolean=false)=EditText(this).apply{
            setText(initial)
            hint=hintText
            if(minLines>1)this.minLines=minLines
            if(multiline){
                setSingleLine(false)
                maxLines=8
                gravity=Gravity.TOP or Gravity.START
                setHorizontallyScrolling(false)
                inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            setTextColor(fieldText)
            setHintTextColor(fieldHint)
            if(android.os.Build.VERSION.SDK_INT>=21) backgroundTintList=android.content.res.ColorStateList.valueOf(if(dark)0xff64748b.toInt() else 0xff94a3b8.toInt())
        }
        val label=edit(c.label,"Line label",3,true)
        val notes=edit(c.notes,"Line metadata / notes",3,true)
        val arrows=Spinner(this).apply{
            adapter=object: ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,ArrowType.values().map{it.name.lowercase().replaceFirstChar{c->c.uppercase()}}){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())}}
            }
            setSelection(c.arrowType.ordinal)
            setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
        val styles=Spinner(this).apply{
            adapter=object: ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,LineStyle.values().map{it.name.lowercase().replaceFirstChar{c->c.uppercase()}}){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())}}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{return super.getDropDownView(position,convertView,parent).apply{setBackgroundColor(if(dark)0xff1e293b.toInt() else Color.WHITE);(this as? TextView)?.setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())}}
            }
            setSelection(c.lineStyle.ordinal)
            setBackgroundColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
        }
        val color=connectionColorSpinner(c.color)
        val labelColor=labelColorSpinner(c.labelColor)
        val labelTextSize=textSizeSpinner(c.labelTextSize)
        val labelFont=fontSpinner(c.labelFont)
        val labelStyleChecks=textStyleChecks(c.labelBold,c.labelItalic,c.labelUnderline,dark)
        val thickness=thicknessSpinner(c.thickness)

        box.addView(editorLabel("Label"));box.addView(label)
        box.addView(editorLabel("Label Color"));box.addView(labelColor)
        box.addView(editorLabel("Text size"));box.addView(labelTextSize)
        box.addView(editorLabel("Font"));box.addView(labelFont)
        box.addView(editorLabel("Text style"));box.addView(labelStyleChecks)
        box.addView(editorLabel("Notes"));box.addView(notes)
        box.addView(editorLabel("Arrow"));box.addView(arrows)
        box.addView(editorLabel("Line style"));box.addView(styles)
        box.addView(editorLabel("Line thickness"));box.addView(thickness)
        box.addView(editorLabel("Line colour"));box.addView(color)
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(box)}

        val dialog=dialogBuilder().setTitle("Edit connection").setView(scroll)
            .setPositiveButton("Save"){_,_->
                val before=doc.deepCopy()
                c.label=label.text.toString();c.notes=notes.text.toString()
                c.arrowType=ArrowType.values()[arrows.selectedItemPosition]
                c.lineStyle=LineStyle.values()[styles.selectedItemPosition]
                c.thickness=LineThickness.values()[thickness.selectedItemPosition]
                c.color=connectionColors().values.elementAt(color.selectedItemPosition)
                c.labelColor=labelColors().values.elementAt(labelColor.selectedItemPosition)
                c.labelTextSize=TextSize.values()[labelTextSize.selectedItemPosition]
                c.labelFont=TextFont.values()[labelFont.selectedItemPosition]
                c.labelBold=(labelStyleChecks.getChildAt(0) as CheckBox).isChecked
                c.labelItalic=(labelStyleChecks.getChildAt(1) as CheckBox).isChecked
                c.labelUnderline=(labelStyleChecks.getChildAt(2) as CheckBox).isChecked
                history.record(before,doc.deepCopy());documentDirty=true;canvas.invalidate();updateUi()
            }.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val textColor=if(uiDark)Color.WHITE else 0xff172033.toInt()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(textColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(textColor)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xff0f172a.toInt()))
        }
        dialog.show()
    }

    private fun showConnectionColorPicker(c:FlowConnection){
        val colors=connectionColors()
        val names=colors.keys.toList()
        val adapter=colorSpinnerAdapter(names,colors.values.toList(),uiDark)
        dialogBuilder().setTitle("Connection colour").setAdapter(adapter){_,which->
            val before=doc.deepCopy();c.color=colors[names[which]]!!;history.record(before,doc.deepCopy());documentDirty=true;canvas.invalidate();updateUi()
        }.show()
    }

    private fun parseColor(text:String,fallback:Int):Int{
        val t=text.trim()
        return runCatching{ Color.parseColor(t) }.getOrElse{fallback}
    }

    private fun cloneElement(e:FlowElement){val before=doc.deepCopy();val copy=e.copy(id=java.util.UUID.randomUUID().toString(),x=e.x+maxOf(canvas.gridSize,40f),y=e.y+maxOf(canvas.gridSize,40f));var tries=0;while(doc.elements.any{overlaps(it,copy)}&&tries<20){copy.x+=40f;copy.y+=40f;tries++};doc.elements+=copy;canvas.selectedElementId=copy.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}
    private fun overlaps(a:FlowElement,b:FlowElement)=a.x<b.x+b.width&&a.x+a.width>b.x&&a.y<b.y+b.height&&a.y+a.height>b.y

    private fun saveAsset(e:FlowElement){val input=EditText(this).apply{hint="Building block name";setText(e.label.ifBlank{"Building block"});setTextColor(if(uiDark)Color.WHITE else 0xff172033.toInt());setHintTextColor(if(uiDark)0xff94a3b8.toInt() else 0xff64748b.toInt())};dialogBuilder().setTitle("Save as Building Block").setMessage("Saves this block only — connections are not included.").setView(input).setPositiveButton("Save"){_,_->assets.save(ElementAsset(name=input.text.toString().trim().ifBlank{"Building block"},element=e.copy(id=java.util.UUID.randomUUID().toString(),x=0f,y=0f)));toast("Building block saved")}.setNegativeButton("Cancel",null).show()}
    private fun assetPicker(){
        val list=assets.all()
        if(list.isEmpty()){dialogBuilder().setTitle("Building Blocks").setMessage("No saved building blocks yet. Select a block and use Save Block.").setPositiveButton("OK",null).show();return}
        val names=list.map{it.name}
        showCompactPopup(addButton ?: canvas,"Building Blocks",names,{ which -> insertAsset(list[which]) })
    }
    private fun manageAssets(){val list=assets.all();if(list.isEmpty()){toast("No building blocks");return};val names=list.map{"${it.name} — ${shapeName(it.element.shape)}"}.toTypedArray();dialogBuilder().setTitle("Manage Building Blocks").setItems(names){_,which->dialogBuilder().setTitle(list[which].name).setItems(arrayOf("Insert","Delete")){_,a->if(a==0)insertAsset(list[which])else{assets.delete(list[which].id);toast("Deleted")}}.show()}.setPositiveButton("Done",null).show()}
    private fun insertAsset(a:ElementAsset){val before=doc.deepCopy();val e=a.element.copy(id=java.util.UUID.randomUUID().toString(),x=300f,y=220f);doc.elements+=e;canvas.selectedElementId=e.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());documentDirty=true;canvas.invalidate();updateUi()}
    private fun showNotes(e:FlowElement){dialogBuilder().setTitle("Notes — ${e.label}").setMessage(e.notes.ifBlank{"No notes attached."}).setPositiveButton("Close",null).show()}
    private fun templates(){
        val built=Templates.all()
        val names=built.map{it.first}
        showCompactPopup(addButton ?: canvas,"Templates",names,{ which -> chooseTemplate(built[which].second()) })
    }

    private fun chooseTemplate(template:FlowDocument){
        if(documentDirty){
            dialogBuilder().setTitle("Save changes?")
                .setMessage("\"$documentName\" has unsaved changes. Save before loading this template?")
                .setNegativeButton("Cancel",null)
                .setNeutralButton("Don't Save"){_,_->applyTemplate(template)}
                .setPositiveButton("Save"){_,_->saveCurrentThen{applyTemplate(template)}}
                .show()
        } else applyTemplate(template)
    }

    private fun applyTemplate(template:FlowDocument){
        history=HistoryManager(2000)
        doc=template
        canvas.document=doc
        canvas.selectedElementId=null
        canvas.selectedConnectionId=null
        documentName="Untitled"
        documentUri=null
        documentFormat="NONE"
        documentDirty=true
        canvas.fitContent()
        canvas.invalidate()
        updateUi()
    }

    private data class RecentDocument(val uri:String,val name:String,val accessed:Long)

    private fun saveCurrentThen(afterSave:()->Unit){
        if(documentUri==null || documentFormat !in setOf("JSON","MERMAID")){ pendingAfterSave=afterSave; startSaveAsJson() }
        else if(saveCurrent()) afterSave()
    }

    private fun saveCurrent():Boolean{
        val uri=documentUri
        if(uri==null || documentFormat !in setOf("JSON","MERMAID")){ startSaveAsJson(); return false }
        return runCatching{
            val text=if(documentFormat=="MERMAID") Mermaid.export(doc) else doc.toJson()
            contentResolver.openOutputStream(uri,"wt")!!.use{it.write(text.toByteArray(Charsets.UTF_8))}
            documentDirty=false; touchRecent(uri,documentName); toast("Saved $documentName"); true
        }.getOrElse{toast("Could not save $documentName");false}
    }

    private fun newFlowForgeFileName(extension:String):String{
        val date=SimpleDateFormat("dd-MM-yyyy",Locale.US).format(Date())
        val number=(100..999).random()
        val ext=extension.removePrefix(".")
        return "FlowForge_${date}_${number.toString().padStart(3,'0')}.$ext"
    }

    private fun newUntitledFileName(extension:String):String{
        val stamp=SimpleDateFormat("yyMMdd_HHmmss",Locale.US).format(Date())
        return "Untitled_$stamp.$extension"
    }

    private fun saveAs(){
        showSaveAsTypeMenu()
    }

    private fun showSaveAsTypeMenu(){
        val labels=listOf("FlowForge JSON","Mermaid","PNG","PNG Dark","PDF","PDF Dark")
        showCenteredCompactPopup("Save As…",labels){which->when(which){
            0->startSaveAsJson()
            1->startSaveAsMermaid()
            2->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="image/png";putExtra(Intent.EXTRA_TITLE,newUntitledFileName("png"));addCategory(Intent.CATEGORY_OPENABLE)},SAVE_IMAGE)
            3->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="image/png";putExtra(Intent.EXTRA_TITLE,newUntitledFileName("dark.png"));addCategory(Intent.CATEGORY_OPENABLE)},SAVE_IMAGE_DARK)
            4->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/pdf";putExtra(Intent.EXTRA_TITLE,newUntitledFileName("pdf"));addCategory(Intent.CATEGORY_OPENABLE)},SAVE_PDF)
            5->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/pdf";putExtra(Intent.EXTRA_TITLE,newUntitledFileName("dark.pdf"));addCategory(Intent.CATEGORY_OPENABLE)},SAVE_PDF_DARK)
        }}
    }

    private fun startSaveAsJson(){
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            type="application/json"; putExtra(Intent.EXTRA_TITLE,if(documentName=="Untitled")newFlowForgeFileName("json") else documentName)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },SAVE_JSON_AS)
    }

    private fun startSaveAsMermaid(){
        pendingText=Mermaid.export(doc)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="text/plain";putExtra(Intent.EXTRA_TITLE,if(documentName=="Untitled")newFlowForgeFileName("mmd") else documentName);addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},SAVE_MERMAID)
    }

    private fun openDocument(){
        if(documentDirty){
            dialogBuilder().setTitle("Save changes?")
                .setMessage("\\\"$documentName\\\" has unsaved changes. Save changes before opening another canvas?")
                .setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->openDocumentPicker()}
                .setPositiveButton("Save"){_,_->saveCurrentThen(::openDocumentPicker)}.show()
        } else openDocumentPicker()
    }

    private fun openDocumentPicker(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            type="*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/plain","text/markdown"))
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },OPEN_JSON)
    }

    private fun recents(){
        val items=loadRecents()
        if(items.isEmpty()){dialogBuilder().setTitle("Recents").setMessage("No recent canvases yet.").setPositiveButton("OK",null).show();return}
        val labels=items.map{"${it.name}\nLast opened: ${DateFormat.format("d MMM yyyy, h:mm a",Date(it.accessed))}"}.toTypedArray()
        dialogBuilder().setTitle("Recents").setItems(labels){_,which->openRecent(items[which])}.setNegativeButton("Close",null).show()
    }

    private fun openRecent(recent:RecentDocument){
        val uri=Uri.parse(recent.uri)
        if(documentDirty){
            dialogBuilder().setTitle("Save changes?")
                .setMessage("\\\"$documentName\\\" has unsaved changes. Save changes before opening \\\"${recent.name}\\\"?")
                .setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->loadRecentDocument(uri,recent.name)}
                .setPositiveButton("Save"){_,_->saveCurrentThen{loadRecentDocument(uri,recent.name)}}.show()
        } else loadRecentDocument(uri,recent.name)
    }

    private fun loadRecentDocument(uri:Uri,name:String){
        runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{FlowDocument.fromJson(it.readText())}}
            .onSuccess{history=HistoryManager(2000);doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentName=displayDocumentName(name);documentUri=uri;documentFormat="JSON";documentDirty=false;touchRecent(uri,displayDocumentName(name));canvas.invalidate();updateUi()}
            .onFailure{toast("Could not open $name")}
    }

    private fun loadRecents():List<RecentDocument>{
        val raw=prefs.getString("recents","") ?: ""
        return raw.lineSequence().mapNotNull{line->
            val p=line.split('|'); if(p.size!=3)return@mapNotNull null
            runCatching{RecentDocument(String(Base64.getDecoder().decode(p[0]),Charsets.UTF_8),String(Base64.getDecoder().decode(p[1]),Charsets.UTF_8),p[2].toLong())}.getOrNull()
        }.sortedByDescending{it.accessed}.toList()
    }

    private fun touchRecent(uri:Uri,name:String){
        val updated=(listOf(RecentDocument(uri.toString(),name,System.currentTimeMillis()))+loadRecents().filterNot{it.uri==uri.toString()}).take(20)
        val raw=updated.joinToString("\n"){ "${Base64.getEncoder().encodeToString(it.uri.toByteArray(Charsets.UTF_8))}|${Base64.getEncoder().encodeToString(it.name.toByteArray(Charsets.UTF_8))}|${it.accessed}" }
        prefs.edit().putString("recents",raw).apply()
    }

    private fun importMenu(){
        dialogBuilder().setTitle("Import").setItems(arrayOf("FlowForge JSON","Mermaid")){_,w->
            val types=if(w==0)arrayOf("application/json") else arrayOf("text/*")
            val request=if(w==0)IMPORT_JSON else OPEN_MERMAID
            fun go(){openFile(types,request)}
            if(documentDirty) dialogBuilder().setTitle("Save changes?").setMessage("\"$documentName\" has unsaved changes. Save before importing?")
                .setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->go()}.setPositiveButton("Save"){_,_->saveCurrentThen{go()}}.show()
            else go()
        }.show()
    }
    private fun saveText(text:String,mime:String,name:String,request:Int){pendingText=text;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,name)},request)}
    private fun createFile(mime:String,name:String,request:Int){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,name)},request)}
    private fun openFile(types:Array<String>,request:Int){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type=types.first();putExtra(Intent.EXTRA_MIME_TYPES,types);addCategory(Intent.CATEGORY_OPENABLE)},request)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK||data?.data==null){if(requestCode==SAVE_JSON_AS)pendingAfterSave=null;return}
        val uri=data.data!!
        when(requestCode){
            SAVE_JSON->runCatching{contentResolver.openOutputStream(uri)!!.use{it.write(pendingText.toByteArray(Charsets.UTF_8))};toast("Saved file")}.onFailure{toast("Could not save file")}
            SAVE_MERMAID->runCatching{
                runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                contentResolver.openOutputStream(uri,"wt")!!.use{it.write(pendingText.toByteArray(Charsets.UTF_8))}
                documentUri=uri; documentName=displayDocumentName(queryDisplayName(uri) ?: "FlowForge document"); documentFormat="MERMAID"; documentDirty=false; touchRecent(uri,documentName); toast("Saved $documentName")
                pendingAfterSave?.invoke()
            }.onFailure{toast("Could not save Mermaid")}.also{pendingAfterSave=null}

            SAVE_JSON_AS->runCatching{
                runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                contentResolver.openOutputStream(uri,"wt")!!.use{it.write(doc.toJson().toByteArray(Charsets.UTF_8))}
                documentUri=uri; documentName=displayDocumentName(queryDisplayName(uri) ?: "FlowForge document"); documentFormat="JSON"; documentDirty=false; touchRecent(uri,documentName); toast("Saved $documentName")
                pendingAfterSave?.invoke()
            }.onFailure{toast("Could not save document")}.also{pendingAfterSave=null}
            OPEN_JSON->openDocumentFile(uri); IMPORT_JSON->importJson(uri); OPEN_MERMAID->importMermaid(uri); SAVE_PDF->exportPdf(uri,false); SAVE_PDF_DARK->exportPdf(uri,true); SAVE_IMAGE->exportPng(uri,false); SAVE_IMAGE_DARK->exportPng(uri,true)
        }
    }


    private fun openDocumentFile(uri:Uri){
        val name=queryDisplayName(uri) ?: ""
        if(name.endsWith(".mmd",ignoreCase=true) || name.endsWith(".mermaid",ignoreCase=true)) openMermaidDocument(uri)
        else openJsonDocument(uri)
    }

    private fun openMermaidDocument(uri:Uri){
        runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{Mermaid.import(it.readText())}}
            .onSuccess{
                history=HistoryManager(2000)
                doc=it
                canvas.document=doc
                canvas.selectedElementId=null
                canvas.selectedConnectionId=null
                documentName=queryDisplayName(uri) ?: "FlowForge document.mmd"
                documentUri=uri
                documentFormat="MERMAID"
                documentDirty=false
                touchRecent(uri,documentName)
                canvas.fitContent()
                canvas.invalidate()
                updateUi()
            }
            .onFailure{toast("Could not open ${queryDisplayName(uri) ?: "document"}")}
    }

    private fun openJsonDocument(uri:Uri){
        runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{FlowDocument.fromJson(it.readText())}}
            .onSuccess{
                history=HistoryManager(2000)
                doc=it
                canvas.document=doc
                canvas.selectedElementId=null
                canvas.selectedConnectionId=null
                documentName=displayDocumentName(queryDisplayName(uri) ?: "FlowForge document")
                documentUri=uri
                documentDirty=false
                touchRecent(uri,documentName)
                canvas.fitContent()
                canvas.invalidate()
                updateUi()
            }
            .onFailure{toast("Could not open ${queryDisplayName(uri) ?: "document"}")}
    }
    private fun displayDocumentName(name:String):String{
        return name.trim().ifBlank{"Untitled"}
    }

    private fun queryDisplayName(uri:Uri):String?{
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())return it.getString(0)}
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun importJson(uri:Uri){
        val load={runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{FlowDocument.fromJson(it.readText())}}}
        fun go(){load().onSuccess{history=HistoryManager(2000);doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentName=queryDisplayName(uri) ?: "Imported canvas";documentUri=null;documentFormat="NONE";documentDirty=true;canvas.invalidate();updateUi()}.onFailure{toast("Could not import FlowForge JSON")}}
        if(documentDirty)dialogBuilder().setTitle("Save changes?").setMessage("\\\"$documentName\\\" has unsaved changes. Save before importing?").setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->go()}.setPositiveButton("Save"){_,_->saveCurrentThen { go() }}.show() else go()
    }

    private fun importMermaid(uri:Uri){
        val load={runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{Mermaid.import(it.readText())}}}
        fun go(){load().onSuccess{history=HistoryManager(2000);doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentName=queryDisplayName(uri) ?: "Imported Mermaid";documentUri=null;documentFormat="NONE";documentDirty=true;canvas.invalidate();updateUi()}.onFailure{toast("Could not import Mermaid")}}
        if(documentDirty)dialogBuilder().setTitle("Save changes?").setMessage("\\\"$documentName\\\" has unsaved changes. Save before importing?").setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->go()}.setPositiveButton("Save"){_,_->saveCurrentThen { go() }}.show() else go()
    }

    private fun exportPdf(uri:Uri,dark:Boolean){
        val oldDark=canvas.darkMode; val oldGrid=canvas.gridVisible
        canvas.darkMode=dark; canvas.gridVisible=false
        runCatching{
            val pdf=PdfDocument(); val page=pdf.startPage(PdfDocument.PageInfo.Builder(1600,1000,1).create())
            page.canvas.drawColor(if(dark)0xff0f172a.toInt() else Color.WHITE)
            drawDocument(page.canvas,1600f,1000f); pdf.finishPage(page)
            contentResolver.openOutputStream(uri)?.use{pdf.writeTo(it)}; pdf.close()
        }.onFailure{toast("Could not save PDF")}
        canvas.darkMode=oldDark; canvas.gridVisible=oldGrid; canvas.invalidate(); updateUi()
    }
    private fun exportPng(uri:Uri,dark:Boolean){
        val oldDark=canvas.darkMode; val oldGrid=canvas.gridVisible
        canvas.darkMode=dark; canvas.gridVisible=false
        runCatching{
            val b=Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888); val c=Canvas(b)
            c.drawColor(if(dark)0xff0f172a.toInt() else Color.TRANSPARENT); drawDocument(c,1600f,1000f)
            contentResolver.openOutputStream(uri)?.use{b.compress(Bitmap.CompressFormat.PNG,100,it)}; b.recycle()
        }.onFailure{toast("Could not save PNG")}
        canvas.darkMode=oldDark; canvas.gridVisible=oldGrid; canvas.invalidate(); updateUi()
    }
    private fun drawDocument(target:Canvas,w:Float,h:Float){if(doc.elements.isEmpty())return;val minX=doc.elements.minOf{it.x};val minY=doc.elements.minOf{it.y};val maxX=doc.elements.maxOf{it.x+it.width};val maxY=doc.elements.maxOf{it.y+it.height};val pad=80f;val sx=w/(maxX-minX+pad*2);val sy=h/(maxY-minY+pad*2);val sc=min(sx,sy).coerceAtMost(2f);target.save();target.translate(w/2f-(minX+maxX)/2f*sc,h/2f-(minY+maxY)/2f*sc);target.scale(sc,sc);canvas.drawContentForExport(target);target.restore()}
    private fun undo(){history.undo(doc)?.let{doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentDirty=true;canvas.invalidate();updateUi()}}
    private fun redo(){history.redo(doc)?.let{doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentDirty=true;canvas.invalidate();updateUi()}}

    private fun dialogBuilder(): AlertDialog.Builder {
        val theme = if (uiDark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(this, theme)
    }

    private fun fitDiagramToScreen(){
        canvas.resetViewport()
        canvas.fitContent()
    }

    private fun settings(){
        val dark=uiDark
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(8),dp(22),dp(4))
            setBackgroundColor(if(dark) 0xff0f172a.toInt() else Color.WHITE)
        }
        fun check(text:String,value:Boolean,on:(Boolean)->Unit)=CheckBox(this).apply{
            this.text=text; isChecked=value
            setTextColor(if(uiDark) Color.WHITE else 0xff172033.toInt())
            buttonTintList=if(android.os.Build.VERSION.SDK_INT>=21) android.content.res.ColorStateList.valueOf(if(uiDark)0xffcbd5e1.toInt() else 0xff334155.toInt()) else null
            setOnCheckedChangeListener{_,v->on(v)}
        }
        fun settingsButton(text:String, action:()->Unit)=Button(this).apply{
            this.text=text; isAllCaps=false; minHeight=0; minimumHeight=0
            setTextColor(if(uiDark)Color.WHITE else 0xff172033.toInt())
            background=GradientDrawable().apply{
                cornerRadius=dp(8).toFloat()
                setColor(if(uiDark)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
                setStroke(dp(1),if(uiDark)0xff475569.toInt() else 0xffcbd5e1.toInt())
            }
            setOnClickListener{action()}
        }
        val grid=check("Show background grid",canvas.gridVisible){canvas.gridVisible=it;prefs.edit().putBoolean("gridVisible",it).apply();canvas.invalidate()}
        val snap=check("Snap blocks to grid",canvas.snapToGrid){canvas.snapToGrid=it;prefs.edit().putBoolean("snapToGrid",it).apply();updateUi()}
        val darkBox=check("Dark canvas",canvas.darkMode){ }
        box.addView(grid);box.addView(snap);box.addView(darkBox)
        box.addView(settingsButton("Manage Building Blocks"){manageAssets()})
        box.addView(settingsButton("Clear Building Blocks"){assets.clear();toast("Building blocks cleared")})
        val dialog=dialogBuilder().setTitle("Settings").setView(box).setPositiveButton("Done",null).setNegativeButton("Cancel",null).create()
        fun restyleSettings(){
            val nowDark=uiDark
            val textColor=if(nowDark)Color.WHITE else 0xff172033.toInt()
            val buttonBg=if(nowDark)0xff1e293b.toInt() else 0xfff1f5f9.toInt()
            val stroke=if(nowDark)0xff475569.toInt() else 0xffcbd5e1.toInt()
            box.setBackgroundColor(if(nowDark)0xff0f172a.toInt() else Color.WHITE)
            fun restyle(v:View){
                when(v){
                    is CheckBox->{v.setTextColor(textColor);if(android.os.Build.VERSION.SDK_INT>=21)v.buttonTintList=android.content.res.ColorStateList.valueOf(if(nowDark)0xffcbd5e1.toInt() else 0xff334155.toInt())}
                    is Button->{v.setTextColor(textColor);v.background=GradientDrawable().apply{cornerRadius=dp(8).toFloat();setColor(buttonBg);setStroke(dp(1),stroke)}}
                    is TextView->{v.setTextColor(textColor)}
                }
                if(v is ViewGroup)for(i in 0 until v.childCount)restyle(v.getChildAt(i))
            }
            restyle(box)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(textColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(textColor)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if(nowDark)0xff0f172a.toInt() else Color.WHITE))
        }
        darkBox.setOnCheckedChangeListener{_,it->
            canvas.darkMode=it
            prefs.edit().putBoolean("darkMode",it).apply()
            applyThemeChrome()
            restyleSettings()
            canvas.invalidate()
            updateUi()
        }
        dialog.setOnShowListener{
            val buttonColor=Color.WHITE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xff0f172a.toInt()))
        }
        dialog.show()
    }
    private fun applyThemeChrome(){
        // The application chrome is permanently dark. The canvas alone may be light/dark.
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        if(android.os.Build.VERSION.SDK_INT>=28) window.navigationBarDividerColor=Color.TRANSPARENT
        val flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.decorView.systemUiVisibility=flags
        window.decorView.findViewById<View>(android.R.id.content)?.let{root->
            root.setBackgroundColor(Color.BLACK)
            if(root is ViewGroup && root.childCount>0){
                val content=root.getChildAt(0); content.setBackgroundColor(Color.BLACK)
                if(content is ViewGroup && content.childCount>=3){
                    content.getChildAt(0).setBackgroundColor(0xff020617.toInt())
                    content.getChildAt(1).setBackgroundColor(0xff273449.toInt())
                    content.getChildAt(2).setBackgroundColor(0xff111827.toInt())
                    val ctx=content.getChildAt(2)
                    fun recolor(v:View){
                        when(v){
                            is TextView -> v.setTextColor(Color.WHITE)
                            is Button -> {
                                v.setTextColor(Color.WHITE)
                                v.background=GradientDrawable().apply{cornerRadius=dp(8).toFloat();setColor(0xff1e293b.toInt());setStroke(dp(1),0xff475569.toInt())}
                            }
                        }
                        if(v is ViewGroup) for(i in 0 until v.childCount) recolor(v.getChildAt(i))
                    }
                    recolor(ctx)
                }
            }
        }
    }
    private fun applyPreferences(){canvas.gridVisible=prefs.getBoolean("gridVisible",true);canvas.snapToGrid=prefs.getBoolean("snapToGrid",true);canvas.gridSize=prefs.getFloat("gridSize",40f);canvas.darkMode=prefs.getBoolean("darkMode",false);canvas.document=doc;applyThemeChrome();updateUi()}
    private fun shapeName(s:ShapeType)=when(s){
        ShapeType.RECTANGLE->"Rectangle (sharp edges)"
        ShapeType.ROUNDED->"Rectangle (round edges)"
        ShapeType.EXTRA_ROUNDED->"Rectangle (extra round edges)"
        ShapeType.OVAL->"Oval"
        ShapeType.TRIANGLE->"Triangle"
        ShapeType.STAR->"Star"
        ShapeType.CLOUD->"Cloud"
        ShapeType.TRAPEZOID_TOP_SHORT->"Trapezoid (shorter top)"
        ShapeType.TRAPEZOID_BOTTOM_SHORT->"Trapezoid (shorter bottom)"
        else->s.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
    }
    private fun dp(v:Int)= (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
