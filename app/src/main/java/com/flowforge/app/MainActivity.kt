package com.flowforge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
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
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Base64
import java.util.Date

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
    private var addButton: Button? = null
    private var pendingText = ""
    private var documentName = "Untitled"
    private var documentUri: Uri? = null
    private var documentDirty = false
    private var pendingAfterSave:(()->Unit)? = null

    companion object {
        private const val SAVE_JSON = 10; private const val SAVE_MERMAID = 11
        private const val OPEN_JSON = 12; private const val OPEN_MERMAID = 13
        private const val SAVE_PDF = 14; private const val SAVE_IMAGE = 15; private const val SAVE_JSON_AS = 16; private const val IMPORT_JSON = 17
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
    }

    private fun buildUi() {
        // Initialize the canvas before constructing any UI that reads its settings.
        canvas = FlowCanvasView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (canvas.darkMode) 0xff0f172a.toInt() else Color.WHITE)
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
            setBackgroundColor(if (canvas.darkMode) 0xff020617.toInt() else 0xff0f172a.toInt())
        }
        top.addView(iconButton("☰", "Menu") { mainMenu() })
        top.addView(TextView(this).apply {
            text = "FlowForge"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(iconButton("↶", "Undo") { undo() }.also { undoButton = it })
        top.addView(iconButton("↷", "Redo") { redo() }.also { redoButton = it })
        top.addView(iconButton("＋", "Add") { addMenu() }.also { addButton = it })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(62)))

        status = TextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if (canvas.darkMode) 0xffcbd5e1.toInt() else 0xff475569.toInt()); setBackgroundColor(if (canvas.darkMode) 0xff1e293b.toInt() else 0xffe2e8f0.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        contextBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(if (canvas.darkMode) 0xff111827.toInt() else 0xfff8fafc.toInt()); visibility = View.GONE
        }
        contextScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(contextBar)
        }
        root.addView(contextScroll, LinearLayout.LayoutParams(-1, dp(58)))

        canvas.onSelectionChanged = { updateUi() }
        canvas.onDoubleTapElement = { showElementEditor(it) }
        canvas.onNotesTap = { showNotes(it) }
        canvas.onConnectionRequested = { from, to -> createConnection(from, to) }
        canvas.onConnectionCancelled = { updateUi() }
        canvas.onMoveFinished = { e, oldX, oldY ->
            val before=doc.deepCopy(); before.elements.firstOrNull{it.id==e.id}?.apply{x=oldX;y=oldY}
            history.record(before,doc.deepCopy()); documentDirty=true; updateUi()
        }
        canvas.onResizeFinished = { e, oldX, oldY, oldW, oldH ->
            val before=doc.deepCopy(); before.elements.firstOrNull{it.id==e.id}?.apply{x=oldX;y=oldY;width=oldW;height=oldH}
            history.record(before,doc.deepCopy()); documentDirty=true; updateUi()
        }
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        updateUi()
    }

    private fun iconButton(symbol:String, description:String, action:()->Unit) = Button(this).apply {
        text=symbol; contentDescription=description; textSize=21f; minHeight=0; minimumHeight=0
        setPadding(0,0,0,0); isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener{action()}; layoutParams=LinearLayout.LayoutParams(dp(44),dp(52))
    }

    private fun smallButton(label:String, action:()->Unit) = Button(this).apply {
        text=label; textSize=12f; minHeight=0; minimumHeight=0; setPadding(dp(10),0,dp(10),0); isAllCaps=false
        val dark=canvas.darkMode
        setTextColor(if(dark) Color.WHITE else 0xff172033.toInt())
        background=GradientDrawable().apply{
            cornerRadius=dp(8).toFloat()
            setColor(if(dark) 0xff1e293b.toInt() else 0xffe2e8f0.toInt())
            setStroke(dp(1),if(dark) 0xff475569.toInt() else 0xffcbd5e1.toInt())
        }
        stateListAnimator=null
        setOnClickListener{action()}; layoutParams=LinearLayout.LayoutParams(WRAP_CONTENT,dp(46)).apply{setMargins(dp(2),dp(1),dp(2),dp(1))}
    }

    private fun updateUi() {
        undoButton?.isEnabled=history.canUndo(); undoButton?.alpha=if(history.canUndo())1f else 0.45f
        redoButton?.isEnabled=history.canRedo(); redoButton?.alpha=if(history.canRedo())1f else 0.45f
        contextBar?.setBackgroundColor(if(canvas.darkMode)0xff111827.toInt() else 0xfff8fafc.toInt())
        contextScroll?.setBackgroundColor(if(canvas.darkMode)0xff111827.toInt() else 0xfff8fafc.toInt())
        val mode = if (canvas.connectionMode) " • Draw connection: tap/drag from one block to another" else ""
        status?.text="${documentName}${if(documentDirty)" • Unsaved" else ""}  •  ${doc.elements.size} elements  •  ${doc.connections.size} connections$mode"
        val bar=contextBar ?: return
        val scroll=contextScroll ?: return
        bar.removeAllViews()
        val e=canvas.selectedElementId?.let{id->doc.elements.firstOrNull{it.id==id}}
        val c=canvas.selectedConnectionId?.let{id->doc.connections.firstOrNull{it.id==id}}
        if(e!=null && !canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Selected: ${e.label.ifBlank{"Element"}}";textSize=12f;setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt());setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            bar.addView(smallButton("✎ Draw"){canvas.beginConnectionMode()})
            bar.addView(smallButton("Clone"){cloneElement(e)})
            bar.addView(smallButton("Save Block"){saveAsset(e)})
            bar.addView(smallButton("Edit"){showElementEditor(e)})
            bar.addView(smallButton("Reset"){resetElement(e)})
            bar.addView(smallButton("Notes"){showNotes(e)})
            bar.addView(smallButton("Delete"){deleteSelected()})
        } else if(c!=null && !canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Selected connection";textSize=12f;setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt());setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            bar.addView(smallButton("Reverse"){reverseConnection(c)})
            bar.addView(smallButton("Color"){showConnectionColorPicker(c)})
            bar.addView(smallButton("Style: ${lineStyleLabel(c.lineStyle)}"){cycleConnectionLineStyle(c)})
            bar.addView(smallButton("Arrows: ${arrowLabel(c.arrowType)}"){cycleConnectionArrow(c)})
            bar.addView(smallButton("Edit"){showConnectionEditor(c)})
            bar.addView(smallButton("Delete"){deleteSelected()})
        } else if(canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Connection mode";textSize=12f;setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt());setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
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
        val before=doc.deepCopy(); val from=c.fromId;c.fromId=c.toId;c.toId=from;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()
    }

    private fun mainMenu(){
        dialogBuilder().setTitle("FlowForge")
            .setItems(arrayOf("Recents","Save","Save As…","Open…","Fit diagram","Reset zoom / position","Import","Settings")){_,which->when(which){
                0->recents()
                1->saveCurrent()
                2->saveAs()
                3->openDocument()
                4->canvas.fitContent()
                5->canvas.resetViewport()
                6->importMenu()
                7->settings()
            }}.show()
    }

    private fun addMenu(){
        val anchor=addButton ?: return
        showCompactPopup(anchor,"Add",listOf("New canvas","Element","Saved block","Template")){choice->when(choice){
            0->newDocument()
            1->addElement()
            2->assetPicker()
            3->templates()
        }}
    }

    private fun showCompactPopup(anchor:View,title:String,items:List<String>,onChoice:(Int)->Unit){
        val dark=canvas.darkMode
        lateinit var popup: PopupWindow
        val outer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(if(dark)0xff111827.toInt() else Color.WHITE)}
        outer.addView(TextView(this).apply{text=title;textSize=14f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(4),dp(10),dp(8))},LinearLayout.LayoutParams(dp(280),WRAP_CONTENT))
        val scroll=ScrollView(this).apply{isFillViewport=true}
        val listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        items.forEachIndexed{index,label->
            listBox.addView(TextView(this).apply{
                text=label;textSize=16f;gravity=Gravity.CENTER_VERTICAL;setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),0,dp(14),0)
                background=GradientDrawable().apply{cornerRadius=dp(7).toFloat();setColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt());setStroke(dp(1),if(dark)0xff334155.toInt() else 0xffe2e8f0.toInt())}
                setOnClickListener{popup.dismiss();onChoice(index)}
            },LinearLayout.LayoutParams(dp(280),dp(48)).apply{setMargins(0,dp(2),0,dp(2))})
        }
        scroll.addView(listBox)
        outer.addView(scroll,LinearLayout.LayoutParams(WRAP_CONTENT,dp(420)))
        popup=PopupWindow(outer,WRAP_CONTENT,WRAP_CONTENT,true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(10).toFloat();isOutsideTouchable=true}
        popup.showAsDropDown(anchor,-dp(236),dp(2))
    }

    private fun showCenteredCompactPopup(title:String,items:List<String>,onChoice:(Int)->Unit){
        val dark=canvas.darkMode
        lateinit var popup: PopupWindow
        val outer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(if(dark)0xff111827.toInt() else Color.WHITE)}
        outer.addView(TextView(this).apply{text=title;textSize=14f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(10),dp(4),dp(10),dp(8))},LinearLayout.LayoutParams(dp(280),WRAP_CONTENT))
        items.forEachIndexed{index,label->
            outer.addView(TextView(this).apply{
                text=label;textSize=16f;gravity=Gravity.CENTER_VERTICAL;setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(dp(14),0,dp(14),0)
                background=GradientDrawable().apply{cornerRadius=dp(7).toFloat();setColor(if(dark)0xff1e293b.toInt() else 0xfff1f5f9.toInt());setStroke(dp(1),if(dark)0xff334155.toInt() else 0xffe2e8f0.toInt())}
                setOnClickListener{popup.dismiss();onChoice(index)}
            },LinearLayout.LayoutParams(dp(280),dp(48)).apply{setMargins(0,dp(2),0,dp(2))})
        }
        popup=PopupWindow(outer,WRAP_CONTENT,WRAP_CONTENT,true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(10).toFloat();isOutsideTouchable=true}
        popup.showAtLocation(window.decorView,Gravity.CENTER,0,0)
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
        val types=ElementType.values()
        val labels=types.map{it.name.lowercase().replaceFirstChar{c->c.uppercase()}}
        showCompactPopup(addButton ?: canvas,"Add element",labels){which->
            val type=types[which]
            val e=FlowElement(type=type,x=260f+doc.elements.size*35f,y=220f+doc.elements.size*25f,label=type.name.lowercase().replaceFirstChar{it.uppercase()})
            val before=doc.deepCopy();doc.elements+=e;canvas.selectedElementId=e.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());documentDirty=true;canvas.invalidate();updateUi()
        }
    }

    private fun createConnection(fromId:String,toId:String){
        if(fromId==toId)return
        val before=doc.deepCopy(); val c=FlowConnection(fromId=fromId,toId=toId);doc.connections+=c
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

    private fun showElementEditor(e:FlowElement){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,4)}
        val fieldText=if(canvas.darkMode)Color.WHITE else 0xff172033.toInt(); val fieldHint=if(canvas.darkMode)0xff94a3b8.toInt() else 0xff64748b.toInt()
        val label=EditText(this).apply{setText(e.label);hint="Visible label";setTextColor(fieldText);setHintTextColor(fieldHint)}; val notes=EditText(this).apply{setText(e.notes);hint="Metadata / notes";minLines=3;setTextColor(fieldText);setHintTextColor(fieldHint)}
        val shapes=ShapeType.values(); val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,shapes.map{shapeName(it)});setSelection(e.shape.ordinal)}
        box.addView(label);box.addView(TextView(this).apply{text="Shape";setPadding(0,12,0,3)});box.addView(spinner);box.addView(notes)
        dialogBuilder().setTitle("Edit element").setView(box).setPositiveButton("Save"){_,_->val before=doc.deepCopy();e.label=label.text.toString();e.notes=notes.text.toString();e.shape=shapes[spinner.selectedItemPosition];history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}.setNeutralButton("Reset default"){_,_->resetElement(e)}.setNegativeButton("Cancel",null).show()
    }
    private fun resetElement(e:FlowElement){val before=doc.deepCopy();e.shape=FlowElement.defaultShape(e.type);e.width=180f;e.height=90f;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}

    private fun showConnectionEditor(c:FlowConnection){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,4)}
        val fieldText=if(canvas.darkMode)Color.WHITE else 0xff172033.toInt(); val fieldHint=if(canvas.darkMode)0xff94a3b8.toInt() else 0xff64748b.toInt()
        val label=EditText(this).apply{setText(c.label);hint="Line label";setTextColor(fieldText);setHintTextColor(fieldHint)};val notes=EditText(this).apply{setText(c.notes);hint="Line metadata / notes";minLines=3;setTextColor(fieldText);setHintTextColor(fieldHint)}
        val arrows=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,ArrowType.values().map{it.name});setSelection(c.arrowType.ordinal)}
        val styles=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,LineStyle.values().map{it.name});setSelection(c.lineStyle.ordinal)}
        val bx=EditText(this).apply{setText(c.bendX.toString());hint="Bend X";setTextColor(fieldText);setHintTextColor(fieldHint)};val by=EditText(this).apply{setText(c.bendY.toString());hint="Bend Y";setTextColor(fieldText);setHintTextColor(fieldHint)}
        val color=EditText(this).apply{setText(String.format("#%08X",c.color));hint="Line colour (#AARRGGBB)";setTextColor(fieldText);setHintTextColor(fieldHint)}
        box.addView(label);box.addView(notes);box.addView(TextView(this).apply{text="Arrow";setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt())});box.addView(arrows);box.addView(TextView(this).apply{text="Line style";setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt())});box.addView(styles);box.addView(TextView(this).apply{text="Line colour";setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt())});box.addView(color);box.addView(bx);box.addView(by)
        dialogBuilder().setTitle("Edit connection").setView(box).setPositiveButton("Save"){_,_->val before=doc.deepCopy();c.label=label.text.toString();c.notes=notes.text.toString();c.arrowType=ArrowType.values()[arrows.selectedItemPosition];c.lineStyle=LineStyle.values()[styles.selectedItemPosition];c.color=parseColor(color.text.toString(),c.color);c.bendX=bx.text.toString().toFloatOrNull()?:0f;c.bendY=by.text.toString().toFloatOrNull()?:0f;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}.setNegativeButton("Cancel",null).show()
    }

    private fun showConnectionColorPicker(c:FlowConnection){
        val colors=linkedMapOf(
            "Default" to 0xff475569.toInt(), "Black" to Color.BLACK, "White" to Color.WHITE,
            "Red" to 0xffdc2626.toInt(), "Orange" to 0xffea580c.toInt(), "Yellow" to 0xffca8a04.toInt(),
            "Green" to 0xff16a34a.toInt(), "Blue" to 0xff2563eb.toInt(), "Purple" to 0xff7c3aed.toInt(),
            "Pink" to 0xffdb2777.toInt(), "Teal" to 0xff0f766e.toInt(), "Gray" to 0xff64748b.toInt()
        )
        val names=colors.keys.toTypedArray()
        dialogBuilder().setTitle("Connection colour").setItems(names){_,which->
            val before=doc.deepCopy(); c.color=colors[names[which]]!!; history.record(before,doc.deepCopy()); canvas.invalidate(); updateUi()
        }.setNeutralButton("Custom"){
            _,_->val input=EditText(this).apply{setText(String.format("#%08X",c.color));hint="#AARRGGBB or #RRGGBB";setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt());setHintTextColor(if(canvas.darkMode)0xff94a3b8.toInt() else 0xff64748b.toInt())}
            dialogBuilder().setTitle("Custom line colour").setView(input).setPositiveButton("Apply"){_,_->
                val parsed=parseColor(input.text.toString(),c.color); val before=doc.deepCopy(); c.color=parsed; history.record(before,doc.deepCopy()); canvas.invalidate(); updateUi()
            }.setNegativeButton("Cancel",null).show()
        }.show()
    }

    private fun parseColor(text:String,fallback:Int):Int{
        val t=text.trim()
        return runCatching{ Color.parseColor(t) }.getOrElse{fallback}
    }

    private fun cloneElement(e:FlowElement){val before=doc.deepCopy();val copy=e.copy(id=java.util.UUID.randomUUID().toString(),x=e.x+maxOf(canvas.gridSize,40f),y=e.y+maxOf(canvas.gridSize,40f));var tries=0;while(doc.elements.any{overlaps(it,copy)}&&tries<20){copy.x+=40f;copy.y+=40f;tries++};doc.elements+=copy;canvas.selectedElementId=copy.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}
    private fun overlaps(a:FlowElement,b:FlowElement)=a.x<b.x+b.width&&a.x+a.width>b.x&&a.y<b.y+b.height&&a.y+a.height>b.y

    private fun saveAsset(e:FlowElement){val input=EditText(this).apply{hint="Building block name";setText(e.label.ifBlank{"Building block"});setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt());setHintTextColor(if(canvas.darkMode)0xff94a3b8.toInt() else 0xff64748b.toInt())};dialogBuilder().setTitle("Save as Building Block").setMessage("Saves this element only — connections are not included.").setView(input).setPositiveButton("Save"){_,_->assets.save(ElementAsset(name=input.text.toString().trim().ifBlank{"Building block"},element=e.copy(id=java.util.UUID.randomUUID().toString(),x=0f,y=0f)));toast("Building block saved")}.setNegativeButton("Cancel",null).show()}
    private fun assetPicker(){
        val list=assets.all()
        if(list.isEmpty()){dialogBuilder().setTitle("Building Blocks").setMessage("No saved building blocks yet. Select an element and use Save Block.").setPositiveButton("OK",null).show();return}
        val names=list.map{it.name}
        showCompactPopup(addButton ?: canvas,"Building Blocks",names){which->insertAsset(list[which])}
    }
    private fun manageAssets(){val list=assets.all();if(list.isEmpty()){toast("No building blocks");return};val names=list.map{"${it.name} — ${shapeName(it.element.shape)}"}.toTypedArray();dialogBuilder().setTitle("Manage Building Blocks").setItems(names){_,which->dialogBuilder().setTitle(list[which].name).setItems(arrayOf("Insert","Delete")){_,a->if(a==0)insertAsset(list[which])else{assets.delete(list[which].id);toast("Deleted")}}.show()}.setPositiveButton("Done",null).show()}
    private fun insertAsset(a:ElementAsset){val before=doc.deepCopy();val e=a.element.copy(id=java.util.UUID.randomUUID().toString(),x=300f,y=220f);doc.elements+=e;canvas.selectedElementId=e.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());documentDirty=true;canvas.invalidate();updateUi()}
    private fun showNotes(e:FlowElement){dialogBuilder().setTitle("Notes — ${e.label}").setMessage(e.notes.ifBlank{"No notes attached."}).setPositiveButton("Close",null).show()}
    private fun templates(){
        val built=Templates.all()
        val names=built.map{"Template • ${it.first}"}
        showCompactPopup(addButton ?: canvas,"Diagram Templates",names){which->chooseTemplate(built[which].second())}
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
        documentDirty=true
        canvas.fitContent()
        canvas.invalidate()
        updateUi()
    }

    private data class RecentDocument(val uri:String,val name:String,val accessed:Long)

    private fun saveCurrentThen(afterSave:()->Unit){
        if(documentUri==null){ pendingAfterSave=afterSave; startSaveAsJson() }
        else if(saveCurrent()) afterSave()
    }

    private fun saveCurrent():Boolean{
        val uri=documentUri ?: run { startSaveAsJson(); return false }
        return runCatching{
            contentResolver.openOutputStream(uri,"wt")!!.use{it.write(doc.toJson().toByteArray())}
            documentDirty=false; touchRecent(uri,documentName); toast("Saved $documentName"); true
        }.getOrElse{toast("Could not save $documentName");false}
    }

    private fun saveAs(){
        showSaveAsTypeMenu()
    }

    private fun showSaveAsTypeMenu(){
        val labels=listOf("FlowForge JSON","Mermaid","PDF","JPG")
        showCenteredCompactPopup("Save As…",labels){which->when(which){
            0->startSaveAsJson()
            1->startSaveAsMermaid()
            2->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/pdf";putExtra(Intent.EXTRA_TITLE,"flowchart.pdf");addCategory(Intent.CATEGORY_OPENABLE)},SAVE_PDF)
            3->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="image/jpeg";putExtra(Intent.EXTRA_TITLE,"flowchart.jpg");addCategory(Intent.CATEGORY_OPENABLE)},SAVE_IMAGE)
        }}
    }

    private fun startSaveAsJson(){
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            type="application/json"; putExtra(Intent.EXTRA_TITLE,if(documentName=="Untitled")"flowchart.flowforge.json" else documentName)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },SAVE_JSON_AS)
    }

    private fun startSaveAsMermaid(){
        pendingText=Mermaid.export(doc)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="text/plain";putExtra(Intent.EXTRA_TITLE,"flowchart.mmd");addCategory(Intent.CATEGORY_OPENABLE)},SAVE_MERMAID)
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
            type="application/json"
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
            .onSuccess{history=HistoryManager(2000);doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentName=name;documentUri=uri;documentDirty=false;touchRecent(uri,name);canvas.invalidate();updateUi()}
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

    private fun importMenu(){dialogBuilder().setTitle("Import").setItems(arrayOf("FlowForge JSON","Mermaid")){_,w->openFile(if(w==0)arrayOf("application/json") else arrayOf("text/*"),if(w==0)IMPORT_JSON else OPEN_MERMAID)}.show()}
    private fun saveText(text:String,mime:String,name:String,request:Int){pendingText=text;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,name)},request)}
    private fun createFile(mime:String,name:String,request:Int){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,name)},request)}
    private fun openFile(types:Array<String>,request:Int){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type=types.first();putExtra(Intent.EXTRA_MIME_TYPES,types);addCategory(Intent.CATEGORY_OPENABLE)},request)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK||data?.data==null){if(requestCode==SAVE_JSON_AS)pendingAfterSave=null;return}
        val uri=data.data!!
        when(requestCode){
            SAVE_JSON->runCatching{contentResolver.openOutputStream(uri)!!.use{it.write(pendingText.toByteArray())};toast("Saved file")}.onFailure{toast("Could not save file")}
            SAVE_MERMAID->runCatching{contentResolver.openOutputStream(uri)!!.use{it.write(pendingText.toByteArray())};toast("Saved Mermaid")}.onFailure{toast("Could not save Mermaid")}

            SAVE_JSON_AS->runCatching{
                runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                contentResolver.openOutputStream(uri,"wt")!!.use{it.write(doc.toJson().toByteArray())}
                documentUri=uri; documentName=queryDisplayName(uri) ?: "FlowForge document"; documentDirty=false; touchRecent(uri,documentName); toast("Saved $documentName")
                pendingAfterSave?.invoke()
            }.onFailure{toast("Could not save document")}.also{pendingAfterSave=null}
            OPEN_JSON->openJsonDocument(uri); IMPORT_JSON->importJson(uri); OPEN_MERMAID->importMermaid(uri); SAVE_PDF->exportPdf(uri); SAVE_IMAGE->exportJpg(uri)
        }
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
                documentName=queryDisplayName(uri) ?: "FlowForge document"
                documentUri=uri
                documentDirty=false
                touchRecent(uri,documentName)
                canvas.fitContent()
                canvas.invalidate()
                updateUi()
            }
            .onFailure{toast("Could not open ${queryDisplayName(uri) ?: "document"}")}
    }
    private fun queryDisplayName(uri:Uri):String?{
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())return it.getString(0)}
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun importJson(uri:Uri){
        val load={runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{FlowDocument.fromJson(it.readText())}}}
        fun go(){load().onSuccess{history=HistoryManager(2000);doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentName=queryDisplayName(uri) ?: "Imported canvas";documentUri=null;documentDirty=true;canvas.invalidate();updateUi()}.onFailure{toast("Could not import FlowForge JSON")}}
        if(documentDirty)dialogBuilder().setTitle("Save changes?").setMessage("\\\"$documentName\\\" has unsaved changes. Save before importing?").setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->go()}.setPositiveButton("Save"){_,_->saveCurrentThen { go() }}.show() else go()
    }

    private fun importMermaid(uri:Uri){
        val load={runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{Mermaid.import(it.readText())}}}
        fun go(){load().onSuccess{history=HistoryManager(2000);doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentName=queryDisplayName(uri) ?: "Imported Mermaid";documentUri=null;documentDirty=true;canvas.invalidate();updateUi()}.onFailure{toast("Could not import Mermaid")}}
        if(documentDirty)dialogBuilder().setTitle("Save changes?").setMessage("\\\"$documentName\\\" has unsaved changes. Save before importing?").setNegativeButton("Cancel",null).setNeutralButton("Don't Save"){_,_->go()}.setPositiveButton("Save"){_,_->saveCurrentThen { go() }}.show() else go()
    }

    private fun exportPdf(uri:Uri){val old=canvas.gridVisible;canvas.gridVisible=prefs.getBoolean("exportGrid",false);val pdf=PdfDocument();val page=pdf.startPage(PdfDocument.PageInfo.Builder(1600,1000,1).create());drawDocument(page.canvas,1600f,1000f);pdf.finishPage(page);contentResolver.openOutputStream(uri)?.use{pdf.writeTo(it)};pdf.close();canvas.gridVisible=old;canvas.invalidate()}
    private fun exportJpg(uri:Uri){val old=canvas.gridVisible;canvas.gridVisible=prefs.getBoolean("exportGrid",false);val b=Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(if(canvas.darkMode)0xff0f172a.toInt() else Color.WHITE);drawDocument(c,1600f,1000f);contentResolver.openOutputStream(uri)?.use{b.compress(Bitmap.CompressFormat.JPEG,94,it)};b.recycle();canvas.gridVisible=old;canvas.invalidate()}
    private fun drawDocument(target:Canvas,w:Float,h:Float){if(doc.elements.isEmpty())return;val minX=doc.elements.minOf{it.x};val minY=doc.elements.minOf{it.y};val maxX=doc.elements.maxOf{it.x+it.width};val maxY=doc.elements.maxOf{it.y+it.height};val pad=80f;val sx=w/(maxX-minX+pad*2);val sy=h/(maxY-minY+pad*2);val sc=min(sx,sy).coerceAtMost(2f);target.save();target.translate(w/2f-(minX+maxX)/2f*sc,h/2f-(minY+maxY)/2f*sc);target.scale(sc,sc);canvas.drawContentForExport(target);target.restore()}
    private fun undo(){history.undo(doc)?.let{doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentDirty=true;canvas.invalidate();updateUi()}}
    private fun redo(){history.redo(doc)?.let{doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;documentDirty=true;canvas.invalidate();updateUi()}}

    private fun dialogBuilder(): AlertDialog.Builder {
        val theme = if (canvas.darkMode) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(this, theme)
    }

    private fun settings(){
        val dark=canvas.darkMode
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(8),dp(22),dp(4))
            setBackgroundColor(if(dark) 0xff0f172a.toInt() else Color.WHITE)
        }
        fun check(text:String,value:Boolean,on:(Boolean)->Unit)=CheckBox(this).apply{
            this.text=text; isChecked=value
            setTextColor(if(canvas.darkMode) Color.WHITE else 0xff172033.toInt())
            buttonTintList=if(android.os.Build.VERSION.SDK_INT>=21) android.content.res.ColorStateList.valueOf(if(canvas.darkMode)0xffcbd5e1.toInt() else 0xff334155.toInt()) else null
            setOnCheckedChangeListener{_,v->on(v)}
        }
        fun settingsButton(text:String, action:()->Unit)=Button(this).apply{
            this.text=text; isAllCaps=false; minHeight=0; minimumHeight=0
            setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt())
            background=GradientDrawable().apply{
                cornerRadius=dp(8).toFloat()
                setColor(if(canvas.darkMode)0xff1e293b.toInt() else 0xfff1f5f9.toInt())
                setStroke(dp(1),if(canvas.darkMode)0xff475569.toInt() else 0xffcbd5e1.toInt())
            }
            setOnClickListener{action()}
        }
        val grid=check("Show background grid",canvas.gridVisible){canvas.gridVisible=it;prefs.edit().putBoolean("gridVisible",it).apply();canvas.invalidate()}
        val snap=check("Snap elements to grid",canvas.snapToGrid){canvas.snapToGrid=it;prefs.edit().putBoolean("snapToGrid",it).apply();updateUi()}
        val eg=check("Include grid in PDF/JPG export",prefs.getBoolean("exportGrid",false)){prefs.edit().putBoolean("exportGrid",it).apply()}
        val darkBox=check("Dark mode",canvas.darkMode){ }
        val sizes=arrayOf(20f,40f,60f,80f)
        val spin=Spinner(this).apply{
            adapter=object: ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,sizes.map{"$it units"}){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{
                    return super.getView(position,convertView,parent).apply{
                        (this as? TextView)?.setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt())
                        setPadding(dp(8),dp(4),dp(8),dp(4))
                    }
                }
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View{
                    return super.getDropDownView(position,convertView,parent).apply{
                        setBackgroundColor(if(canvas.darkMode)0xff1e293b.toInt() else Color.WHITE)
                        (this as? TextView)?.setTextColor(if(canvas.darkMode)Color.WHITE else 0xff172033.toInt())
                    }
                }
            }
            setSelection(sizes.indexOf(canvas.gridSize).coerceAtLeast(0))
        }
        val spacing=TextView(this).apply{text="Grid spacing";setTextColor(if(dark)Color.WHITE else 0xff172033.toInt());setPadding(0,dp(12),0,dp(2))}
        box.addView(grid);box.addView(snap);box.addView(eg);box.addView(darkBox);box.addView(spacing);box.addView(spin)
        box.addView(settingsButton("Fit diagram to screen"){canvas.fitContent()})
        box.addView(settingsButton("Reset zoom / position"){canvas.resetViewport()})
        box.addView(settingsButton("Manage Building Blocks"){manageAssets()})
        box.addView(settingsButton("Clear Building Blocks"){assets.clear();toast("Building blocks cleared")})
        val dialog=dialogBuilder().setTitle("Settings").setView(box).setPositiveButton("Done"){_,_->canvas.gridSize=sizes[spin.selectedItemPosition];prefs.edit().putFloat("gridSize",canvas.gridSize).apply();canvas.invalidate()}.setNegativeButton("Cancel",null).create()
        fun restyleSettings(){
            val nowDark=canvas.darkMode
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
            val buttonColor=if(canvas.darkMode)Color.WHITE else 0xff172033.toInt()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if(canvas.darkMode)0xff0f172a.toInt() else Color.WHITE))
        }
        dialog.show()
    }
    private fun applyThemeChrome(){
        val dark=canvas.darkMode
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        if(android.os.Build.VERSION.SDK_INT>=28) window.navigationBarDividerColor=Color.TRANSPARENT
        var flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if(!dark && android.os.Build.VERSION.SDK_INT>=23) flags=flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if(!dark && android.os.Build.VERSION.SDK_INT>=26) flags=flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility=flags
        window.decorView.findViewById<View>(android.R.id.content)?.let{root->
            root.setBackgroundColor(if(dark)Color.BLACK else Color.WHITE)
            if(root is ViewGroup && root.childCount>0){
                val content=root.getChildAt(0); content.setBackgroundColor(if(dark)Color.BLACK else Color.WHITE)
                if(content is ViewGroup && content.childCount>=3){
                    content.getChildAt(0).setBackgroundColor(if(dark)0xff020617.toInt() else 0xff0f172a.toInt())
                    content.getChildAt(1).setBackgroundColor(if(dark)Color.BLACK else 0xffe2e8f0.toInt())
                    content.getChildAt(2).setBackgroundColor(if(dark)0xff111827.toInt() else 0xfff8fafc.toInt())
                    val ctx=content.getChildAt(2)
                    fun recolor(v:View){
                        when(v){
                            is TextView -> v.setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())
                            is Button -> {
                                v.setTextColor(if(dark)Color.WHITE else 0xff172033.toInt())
                                v.background=GradientDrawable().apply{cornerRadius=dp(8).toFloat();setColor(if(dark)0xff1e293b.toInt() else 0xffe2e8f0.toInt());setStroke(dp(1),if(dark)0xff475569.toInt() else 0xffcbd5e1.toInt())}
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
    private fun shapeName(s:ShapeType)=s.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
    private fun dp(v:Int)= (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
