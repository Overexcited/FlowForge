package com.flowforge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import com.flowforge.app.mermaid.Mermaid
import com.flowforge.app.model.*
import com.flowforge.app.templates.Templates
import com.flowforge.app.ui.FlowCanvasView
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var canvas: FlowCanvasView
    private lateinit var prefs: SharedPreferences
    private lateinit var assets: AssetStore
    private val history = HistoryManager(2000)
    private var doc = FlowDocument()
    private var status: TextView? = null
    private var contextBar: LinearLayout? = null
    private var contextScroll: HorizontalScrollView? = null
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private var pendingText = ""

    companion object {
        private const val SAVE_JSON = 10; private const val SAVE_MERMAID = 11
        private const val OPEN_JSON = 12; private const val OPEN_MERMAID = 13
        private const val SAVE_PDF = 14; private const val SAVE_IMAGE = 15
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff0f172a.toInt()
        window.navigationBarColor = 0xff0f172a.toInt()
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true)
        prefs = getSharedPreferences("flowforge", MODE_PRIVATE)
        assets = AssetStore(prefs)
        buildUi()
        applyPreferences()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
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
            setBackgroundColor(0xff0f172a.toInt())
        }
        top.addView(iconButton("☰", "Menu") { mainMenu() })
        top.addView(TextView(this).apply {
            text = "FlowForge"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(iconButton("↶", "Undo") { undo() }.also { undoButton = it })
        top.addView(iconButton("↷", "Redo") { redo() }.also { redoButton = it })
        top.addView(iconButton("＋", "Add") { addElement() })
        top.addView(iconButton("✎", "Draw link") { canvas.beginConnectionMode() })
        top.addView(iconButton("⋮", "More") { moreMenu() })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(62)))

        status = TextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            setTextColor(0xff475569.toInt()); setBackgroundColor(0xffe2e8f0.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        contextBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0xfff8fafc.toInt()); visibility = View.GONE
        }
        contextScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(contextBar)
        }
        root.addView(contextScroll, LinearLayout.LayoutParams(-1, dp(58)))

        canvas = FlowCanvasView(this)
        canvas.onSelectionChanged = { updateUi() }
        canvas.onDoubleTapElement = { showElementEditor(it) }
        canvas.onNotesTap = { showNotes(it) }
        canvas.onConnectionRequested = { from, to -> createConnection(from, to) }
        canvas.onConnectionCancelled = { updateUi() }
        canvas.onMoveFinished = { e, oldX, oldY ->
            val before=doc.deepCopy(); before.elements.firstOrNull{it.id==e.id}?.apply{x=oldX;y=oldY}
            history.record(before,doc.deepCopy()); updateUi()
        }
        canvas.onResizeFinished = { e, oldX, oldY, oldW, oldH ->
            val before=doc.deepCopy(); before.elements.firstOrNull{it.id==e.id}?.apply{x=oldX;y=oldY;width=oldW;height=oldH}
            history.record(before,doc.deepCopy()); updateUi()
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
        setOnClickListener{action()}; layoutParams=LinearLayout.LayoutParams(WRAP_CONTENT,dp(46)).apply{setMargins(dp(2),dp(1),dp(2),dp(1))}
    }

    private fun updateUi() {
        undoButton?.isEnabled=history.canUndo(); redoButton?.isEnabled=history.canRedo()
        val mode = if (canvas.connectionMode) " • Draw connection: tap/drag from one block to another" else ""
        status?.text="${doc.elements.size} elements  •  ${doc.connections.size} connections  •  ${if(canvas.snapToGrid)"Snap" else "Free"}$mode"
        val bar=contextBar ?: return
        val scroll=contextScroll ?: return
        bar.removeAllViews()
        val e=canvas.selectedElementId?.let{id->doc.elements.firstOrNull{it.id==id}}
        val c=canvas.selectedConnectionId?.let{id->doc.connections.firstOrNull{it.id==id}}
        if(e!=null && !canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Selected: ${e.label.ifBlank{"Element"}}";textSize=12f;setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            bar.addView(smallButton("✎ Draw"){canvas.beginConnectionMode()})
            bar.addView(smallButton("Clone"){cloneElement(e)})
            bar.addView(smallButton("Save Block"){saveAsset(e)})
            bar.addView(smallButton("Edit"){showElementEditor(e)})
            bar.addView(smallButton("Reset"){resetElement(e)})
            bar.addView(smallButton("Notes"){showNotes(e)})
            bar.addView(smallButton("Delete"){deleteSelected()})
        } else if(c!=null && !canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Selected connection";textSize=12f;setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            bar.addView(smallButton("Reverse"){reverseConnection(c)})
            bar.addView(smallButton("Style: ${lineStyleLabel(c.lineStyle)}"){cycleConnectionLineStyle(c)})
            bar.addView(smallButton("Arrows: ${arrowLabel(c.arrowType)}"){cycleConnectionArrow(c)})
            bar.addView(smallButton("Edit"){showConnectionEditor(c)})
            bar.addView(smallButton("Delete"){deleteSelected()})
        } else if(canvas.connectionMode){
            bar.visibility=View.VISIBLE; scroll.visibility=View.VISIBLE
            bar.addView(TextView(this).apply{text="Connection mode";textSize=12f;setPadding(4,0,dp(8),0)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
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
        AlertDialog.Builder(this).setTitle("FlowForge")
            .setItems(arrayOf("New diagram","Add element","Building blocks","Templates","Import","Export","Settings")){_,which->when(which){0->newDocument();1->addElement();2->assetPicker();3->templates();4->importMenu();5->exportMenu();6->settings()}}.show()
    }
    private fun moreMenu(){
        AlertDialog.Builder(this).setTitle("More")
            .setItems(arrayOf("Fit diagram","Reset zoom / position","Building blocks","Templates","Export","Import","Settings")){_,which->when(which){0->canvas.fitContent();1->canvas.resetViewport();2->assetPicker();3->templates();4->exportMenu();5->importMenu();6->settings()}}.show()
    }

    private fun replaceDocument(newDoc: FlowDocument, record:Boolean=true) { if(record)history.record(doc.deepCopy(),newDoc.deepCopy());doc=newDoc;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;updateUi() }
    private fun newDocument(){replaceDocument(FlowDocument())}

    private fun addElement(){
        val types=ElementType.values(); AlertDialog.Builder(this).setTitle("Add element").setItems(types.map{it.name.lowercase().replaceFirstChar{c->c.uppercase()}}.toTypedArray()){_,which->
            val type=types[which]; val e=FlowElement(type=type,x=260f+doc.elements.size*35f,y=220f+doc.elements.size*25f,label=type.name.lowercase().replaceFirstChar{it.uppercase()})
            val before=doc.deepCopy();doc.elements+=e;canvas.selectedElementId=e.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()
        }.show()
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
        val label=EditText(this).apply{setText(e.label);hint="Visible label"}; val notes=EditText(this).apply{setText(e.notes);hint="Metadata / notes";minLines=3}
        val shapes=ShapeType.values(); val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,shapes.map{shapeName(it)});setSelection(e.shape.ordinal)}
        box.addView(label);box.addView(TextView(this).apply{text="Shape";setPadding(0,12,0,3)});box.addView(spinner);box.addView(notes)
        AlertDialog.Builder(this).setTitle("Edit element").setView(box).setPositiveButton("Save"){_,_->val before=doc.deepCopy();e.label=label.text.toString();e.notes=notes.text.toString();e.shape=shapes[spinner.selectedItemPosition];history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}.setNeutralButton("Reset default"){_,_->resetElement(e)}.setNegativeButton("Cancel",null).show()
    }
    private fun resetElement(e:FlowElement){val before=doc.deepCopy();e.shape=FlowElement.defaultShape(e.type);e.width=180f;e.height=90f;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}

    private fun showConnectionEditor(c:FlowConnection){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,4)}
        val label=EditText(this).apply{setText(c.label);hint="Line label"};val notes=EditText(this).apply{setText(c.notes);hint="Line metadata / notes";minLines=3}
        val arrows=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,ArrowType.values().map{it.name});setSelection(c.arrowType.ordinal)}
        val styles=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,LineStyle.values().map{it.name});setSelection(c.lineStyle.ordinal)}
        val bx=EditText(this).apply{setText(c.bendX.toString());hint="Bend X"};val by=EditText(this).apply{setText(c.bendY.toString());hint="Bend Y"}
        box.addView(label);box.addView(notes);box.addView(TextView(this).apply{text="Arrow"});box.addView(arrows);box.addView(TextView(this).apply{text="Line style"});box.addView(styles);box.addView(bx);box.addView(by)
        AlertDialog.Builder(this).setTitle("Edit connection").setView(box).setPositiveButton("Save"){_,_->val before=doc.deepCopy();c.label=label.text.toString();c.notes=notes.text.toString();c.arrowType=ArrowType.values()[arrows.selectedItemPosition];c.lineStyle=LineStyle.values()[styles.selectedItemPosition];c.bendX=bx.text.toString().toFloatOrNull()?:0f;c.bendY=by.text.toString().toFloatOrNull()?:0f;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}.setNegativeButton("Cancel",null).show()
    }

    private fun cloneElement(e:FlowElement){val before=doc.deepCopy();val copy=e.copy(id=java.util.UUID.randomUUID().toString(),x=e.x+maxOf(canvas.gridSize,40f),y=e.y+maxOf(canvas.gridSize,40f));var tries=0;while(doc.elements.any{overlaps(it,copy)}&&tries<20){copy.x+=40f;copy.y+=40f;tries++};doc.elements+=copy;canvas.selectedElementId=copy.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}
    private fun overlaps(a:FlowElement,b:FlowElement)=a.x<b.x+b.width&&a.x+a.width>b.x&&a.y<b.y+b.height&&a.y+a.height>b.y

    private fun saveAsset(e:FlowElement){val input=EditText(this).apply{hint="Building block name";setText(e.label.ifBlank{"Building block"})};AlertDialog.Builder(this).setTitle("Save as Building Block").setMessage("Saves this element only — connections are not included.").setView(input).setPositiveButton("Save"){_,_->assets.save(ElementAsset(name=input.text.toString().trim().ifBlank{"Building block"},element=e.copy(id=java.util.UUID.randomUUID().toString(),x=0f,y=0f)));toast("Building block saved")}.setNegativeButton("Cancel",null).show()}
    private fun assetPicker(){val list=assets.all();if(list.isEmpty()){AlertDialog.Builder(this).setTitle("Building Blocks").setMessage("No saved building blocks yet. Select an element and use Save Block.").setPositiveButton("OK",null).show();return};val names=list.map{it.name}.toTypedArray();AlertDialog.Builder(this).setTitle("Building Blocks").setItems(names){_,which->insertAsset(list[which])}.setNeutralButton("Manage"){_,_->manageAssets()}.setNegativeButton("Cancel",null).show()}
    private fun manageAssets(){val list=assets.all();if(list.isEmpty()){toast("No building blocks");return};val names=list.map{"${it.name} — ${shapeName(it.element.shape)}"}.toTypedArray();AlertDialog.Builder(this).setTitle("Manage Building Blocks").setItems(names){_,which->AlertDialog.Builder(this).setTitle(list[which].name).setItems(arrayOf("Insert","Delete")){_,a->if(a==0)insertAsset(list[which])else{assets.delete(list[which].id);toast("Deleted")}}.show()}.setPositiveButton("Done",null).show()}
    private fun insertAsset(a:ElementAsset){val before=doc.deepCopy();val e=a.element.copy(id=java.util.UUID.randomUUID().toString(),x=300f,y=220f);doc.elements+=e;canvas.selectedElementId=e.id;canvas.selectedConnectionId=null;history.record(before,doc.deepCopy());canvas.invalidate();updateUi()}
    private fun showNotes(e:FlowElement){AlertDialog.Builder(this).setTitle("Notes — ${e.label}").setMessage(e.notes.ifBlank{"No notes attached."}).setPositiveButton("Close",null).show()}
    private fun templates(){val built=Templates.all();val names=built.map{"Template • ${it.first}"}.toTypedArray();AlertDialog.Builder(this).setTitle("Diagram Templates").setItems(names){_,which->replaceDocument(built[which].second())}.setPositiveButton("Close",null).show()}

    private fun exportMenu(){val items=arrayOf("FlowForge JSON","Mermaid","PDF","JPG");AlertDialog.Builder(this).setTitle("Export").setItems(items){_,w->when(w){0->saveText(doc.toJson(),"application/json","flowchart.flowforge.json",SAVE_JSON);1->saveText(Mermaid.export(doc),"text/plain","flowchart.mmd",SAVE_MERMAID);2->createFile("application/pdf","flowchart.pdf",SAVE_PDF);3->createFile("image/jpeg","flowchart.jpg",SAVE_IMAGE)}}.show()}
    private fun importMenu(){AlertDialog.Builder(this).setTitle("Import").setItems(arrayOf("FlowForge JSON","Mermaid")){_,w->openFile(if(w==0)arrayOf("application/json","text/*") else arrayOf("text/*"),if(w==0)OPEN_JSON else OPEN_MERMAID)}.show()}
    private fun saveText(text:String,mime:String,name:String,request:Int){pendingText=text;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,name)},request)}
    private fun createFile(mime:String,name:String,request:Int){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,name)},request)}
    private fun openFile(types:Array<String>,request:Int){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type=types.first();putExtra(Intent.EXTRA_MIME_TYPES,types);addCategory(Intent.CATEGORY_OPENABLE)},request)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data?.data==null)return;val uri=data.data!!;when(requestCode){SAVE_JSON,SAVE_MERMAID->contentResolver.openOutputStream(uri)?.use{it.write(pendingText.toByteArray())};OPEN_JSON->importJson(uri);OPEN_MERMAID->importMermaid(uri);SAVE_PDF->exportPdf(uri);SAVE_IMAGE->exportJpg(uri)}}
    private fun importJson(uri:Uri){runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{FlowDocument.fromJson(it.readText())}}.onSuccess{replaceDocument(it)}.onFailure{toast("Could not import FlowForge JSON")}}
    private fun importMermaid(uri:Uri){runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{Mermaid.import(it.readText())}}.onSuccess{replaceDocument(it)}.onFailure{toast("Could not import Mermaid")}}
    private fun exportPdf(uri:Uri){val old=canvas.gridVisible;canvas.gridVisible=prefs.getBoolean("exportGrid",false);val pdf=PdfDocument();val page=pdf.startPage(PdfDocument.PageInfo.Builder(1600,1000,1).create());drawDocument(page.canvas,1600f,1000f);pdf.finishPage(page);contentResolver.openOutputStream(uri)?.use{pdf.writeTo(it)};pdf.close();canvas.gridVisible=old;canvas.invalidate()}
    private fun exportJpg(uri:Uri){val old=canvas.gridVisible;canvas.gridVisible=prefs.getBoolean("exportGrid",false);val b=Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(if(canvas.darkMode)0xff0f172a.toInt() else Color.WHITE);drawDocument(c,1600f,1000f);contentResolver.openOutputStream(uri)?.use{b.compress(Bitmap.CompressFormat.JPEG,94,it)};b.recycle();canvas.gridVisible=old;canvas.invalidate()}
    private fun drawDocument(target:Canvas,w:Float,h:Float){if(doc.elements.isEmpty())return;val minX=doc.elements.minOf{it.x};val minY=doc.elements.minOf{it.y};val maxX=doc.elements.maxOf{it.x+it.width};val maxY=doc.elements.maxOf{it.y+it.height};val pad=80f;val sx=w/(maxX-minX+pad*2);val sy=h/(maxY-minY+pad*2);val sc=min(sx,sy).coerceAtMost(2f);target.save();target.translate(w/2f-(minX+maxX)/2f*sc,h/2f-(minY+maxY)/2f*sc);target.scale(sc,sc);canvas.drawContentForExport(target);target.restore()}
    private fun undo(){history.undo(doc)?.let{doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;canvas.invalidate();updateUi()}}
    private fun redo(){history.redo(doc)?.let{doc=it;canvas.document=doc;canvas.selectedElementId=null;canvas.selectedConnectionId=null;canvas.invalidate();updateUi()}}

    private fun settings(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,4)}
        fun check(text:String,value:Boolean,on:(Boolean)->Unit)=CheckBox(this).apply{this.text=text;isChecked=value;setOnCheckedChangeListener{_,v->on(v)}}
        val grid=check("Show background grid",canvas.gridVisible){canvas.gridVisible=it;prefs.edit().putBoolean("gridVisible",it).apply();canvas.invalidate()}
        val snap=check("Snap elements to grid",canvas.snapToGrid){canvas.snapToGrid=it;prefs.edit().putBoolean("snapToGrid",it).apply();updateUi()}
        val eg=check("Include grid in PDF/JPG export",prefs.getBoolean("exportGrid",false)){prefs.edit().putBoolean("exportGrid",it).apply()}
        val dark=check("Dark canvas",canvas.darkMode){canvas.darkMode=it;prefs.edit().putBoolean("darkMode",it).apply();canvas.invalidate()}
        val sizes=arrayOf(20f,40f,60f,80f);val spin=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,sizes.map{"$it units"});setSelection(sizes.indexOf(canvas.gridSize).coerceAtLeast(0))}
        box.addView(grid);box.addView(snap);box.addView(eg);box.addView(dark);box.addView(TextView(this).apply{text="Grid spacing";setPadding(0,12,0,2)});box.addView(spin)
        box.addView(Button(this).apply{text="Fit diagram to screen";setOnClickListener{canvas.fitContent()}});box.addView(Button(this).apply{text="Reset zoom / position";setOnClickListener{canvas.resetViewport()}})
        box.addView(Button(this).apply{text="Manage Building Blocks";setOnClickListener{manageAssets()}});box.addView(Button(this).apply{text="Clear Building Blocks";setOnClickListener{assets.clear();toast("Building blocks cleared")}})
        AlertDialog.Builder(this).setTitle("Settings").setView(box).setPositiveButton("Done"){_,_->canvas.gridSize=sizes[spin.selectedItemPosition];prefs.edit().putFloat("gridSize",canvas.gridSize).apply();canvas.invalidate()}.setNegativeButton("Cancel",null).show()
    }
    private fun applyPreferences(){canvas.gridVisible=prefs.getBoolean("gridVisible",true);canvas.snapToGrid=prefs.getBoolean("snapToGrid",true);canvas.gridSize=prefs.getFloat("gridSize",40f);canvas.darkMode=prefs.getBoolean("darkMode",false);canvas.document=doc;updateUi()}
    private fun shapeName(s:ShapeType)=s.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
    private fun dp(v:Int)= (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
