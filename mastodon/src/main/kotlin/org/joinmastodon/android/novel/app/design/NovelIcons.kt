package org.joinmastodon.android.novel.app.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.R

/** Lucide originals, ISC license in assets/novel/lucide/LICENSE. */
object NovelIcons {
    val Novel: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_novel_rebuilt)
    private fun lucide(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()
    val ArrowDownUp: ImageVector by lazy { lucide("ArrowDownUp", "m3 16 4 4 4-4", "M7 20V4", "m21 8-4-4-4 4", "M17 4v16") }
    val ArrowLeft: ImageVector by lazy { lucide("ArrowLeft", "m12 19-7-7 7-7", "M19 12H5") }
    val ArrowRight: ImageVector by lazy { lucide("ArrowRight", "M5 12h14", "m12 5 7 7-7 7") }
    val ArrowUpLeft: ImageVector by lazy { lucide("ArrowUpLeft", "M7 17V7h10", "M17 17 7 7") }
    val BadgeCheck: ImageVector by lazy { lucide("BadgeCheck", "M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z", "m16 9-5.5 5.5L8 12") }
    val BatteryFull: ImageVector by lazy { lucide("BatteryFull", "M10 10v4", "M14 10v4", "M22 14v-4", "M6 10v4", "M4.0 6.0 H16.0 Q18.0 6.0 18.0 8.0 V16.0 Q18.0 18.0 16.0 18.0 H4.0 Q2.0 18.0 2.0 16.0 V8.0 Q2.0 6.0 4.0 6.0 Z") }
    val Bell: ImageVector by lazy { lucide("Bell", "M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326") }
    val BookOpen: ImageVector by lazy { lucide("BookOpen", "M12 5v16", "M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z") }
    val Bookmark: ImageVector by lazy { lucide("Bookmark", "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z") }
    val BookmarkCheck: ImageVector by lazy { lucide("BookmarkCheck", "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z", "m9 10 2 2 4-4") }
    val BookmarkPlus: ImageVector by lazy { lucide("BookmarkPlus", "M12 7v6", "M15 10H9", "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z") }
    val Check: ImageVector by lazy { lucide("Check", "M20 6 9 17l-5-5") }
    val CheckCheck: ImageVector by lazy { lucide("CheckCheck", "M18 6 7 17l-5-5", "m22 10-7.5 7.5L13 16") }
    val ChevronDown: ImageVector by lazy { lucide("ChevronDown", "m6 9 6 6 6-6") }
    val ChevronLeft: ImageVector by lazy { lucide("ChevronLeft", "m15 18-6-6 6-6") }
    val ChevronRight: ImageVector by lazy { lucide("ChevronRight", "m9 18 6-6-6-6") }
    val ChevronUp: ImageVector by lazy { lucide("ChevronUp", "m18 15-6-6-6 6") }
    val Circle: ImageVector by lazy { lucide("Circle", "M2.0 12.0 A10.0 10.0 0 1 0 22.0 12.0 A10.0 10.0 0 1 0 2.0 12.0") }
    val CircleCheck: ImageVector by lazy { lucide("CircleCheck", "M2.0 12.0 A10.0 10.0 0 1 0 22.0 12.0 A10.0 10.0 0 1 0 2.0 12.0", "m16 9-5.5 5.5L8 12") }
    val Clock: ImageVector by lazy { lucide("Clock", "M2.0 12.0 A10.0 10.0 0 1 0 22.0 12.0 A10.0 10.0 0 1 0 2.0 12.0", "M12 6v6l4 2") }
    val Combine: ImageVector by lazy { lucide("Combine", "M14 3a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1", "M19 3a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1", "m7 15 3 3", "m7 21 3-3H5a2 2 0 0 1-2-2v-2", "M15.0 14.0 H20.0 Q21.0 14.0 21.0 15.0 V20.0 Q21.0 21.0 20.0 21.0 H15.0 Q14.0 21.0 14.0 20.0 V15.0 Q14.0 14.0 15.0 14.0 Z", "M4.0 3.0 H9.0 Q10.0 3.0 10.0 4.0 V9.0 Q10.0 10.0 9.0 10.0 H4.0 Q3.0 10.0 3.0 9.0 V4.0 Q3.0 3.0 4.0 3.0 Z") }
    val Compass: ImageVector by lazy { lucide("Compass", "M2.0 12.0 A10.0 10.0 0 1 0 22.0 12.0 A10.0 10.0 0 1 0 2.0 12.0", "m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z") }
    val Download: ImageVector by lazy { lucide("Download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }
    val Ellipsis: ImageVector by lazy { lucide("Ellipsis", "M11.0 12.0 A1.0 1.0 0 1 0 13.0 12.0 A1.0 1.0 0 1 0 11.0 12.0", "M18.0 12.0 A1.0 1.0 0 1 0 20.0 12.0 A1.0 1.0 0 1 0 18.0 12.0", "M4.0 12.0 A1.0 1.0 0 1 0 6.0 12.0 A1.0 1.0 0 1 0 4.0 12.0") }
    val EllipsisVertical: ImageVector by lazy { lucide("EllipsisVertical", "M11.0 12.0 A1.0 1.0 0 1 0 13.0 12.0 A1.0 1.0 0 1 0 11.0 12.0", "M11.0 5.0 A1.0 1.0 0 1 0 13.0 5.0 A1.0 1.0 0 1 0 11.0 5.0", "M11.0 19.0 A1.0 1.0 0 1 0 13.0 19.0 A1.0 1.0 0 1 0 11.0 19.0") }
    val Eye: ImageVector by lazy { lucide("Eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9.0 12.0 A3.0 3.0 0 1 0 15.0 12.0 A3.0 3.0 0 1 0 9.0 12.0") }
    val EyeOff: ImageVector by lazy { lucide("EyeOff", "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20") }
    val FilePenLine: ImageVector by lazy { lucide("FilePenLine", "M14.364 13.634a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506l4.013-4.009a1 1 0 0 0-3.004-3.004z", "M14.487 7.858A1 1 0 0 1 14 7V2", "M20 19.645V20a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l2.516 2.516", "M8 18h1") }
    val FileText: ImageVector by lazy { lucide("FileText", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 9H8", "M16 13H8", "M16 17H8") }
    val FileUp: ImageVector by lazy { lucide("FileUp", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M12 12v6", "m15 15-3-3-3 3") }
    val FolderOpen: ImageVector by lazy { lucide("FolderOpen", "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2") }
    val GripVertical: ImageVector by lazy { lucide("GripVertical", "M8.0 12.0 A1.0 1.0 0 1 0 10.0 12.0 A1.0 1.0 0 1 0 8.0 12.0", "M8.0 5.0 A1.0 1.0 0 1 0 10.0 5.0 A1.0 1.0 0 1 0 8.0 5.0", "M8.0 19.0 A1.0 1.0 0 1 0 10.0 19.0 A1.0 1.0 0 1 0 8.0 19.0", "M14.0 12.0 A1.0 1.0 0 1 0 16.0 12.0 A1.0 1.0 0 1 0 14.0 12.0", "M14.0 5.0 A1.0 1.0 0 1 0 16.0 5.0 A1.0 1.0 0 1 0 14.0 5.0", "M14.0 19.0 A1.0 1.0 0 1 0 16.0 19.0 A1.0 1.0 0 1 0 14.0 19.0") }
    val Heart: ImageVector by lazy { lucide("Heart", "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5") }
    val History: ImageVector by lazy { lucide("History", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2") }
    val Image: ImageVector by lazy { lucide("Image", "M5.0 3.0 H19.0 Q21.0 3.0 21.0 5.0 V19.0 Q21.0 21.0 19.0 21.0 H5.0 Q3.0 21.0 3.0 19.0 V5.0 Q3.0 3.0 5.0 3.0 Z", "M7.0 9.0 A2.0 2.0 0 1 0 11.0 9.0 A2.0 2.0 0 1 0 7.0 9.0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val ImagePlus: ImageVector by lazy { lucide("ImagePlus", "M16 5h6", "M19 2v6", "M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21", "M7.0 9.0 A2.0 2.0 0 1 0 11.0 9.0 A2.0 2.0 0 1 0 7.0 9.0") }
    val Info: ImageVector by lazy { lucide("Info", "M2.0 12.0 A10.0 10.0 0 1 0 22.0 12.0 A10.0 10.0 0 1 0 2.0 12.0", "M12 16v-4", "M12 8h.01") }
    val Library: ImageVector by lazy { lucide("Library", "m16 6 4 14", "M12 6v14", "M8 8v12", "M4 4v16") }
    val List: ImageVector by lazy { lucide("List", "M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13") }
    val ListChecks: ImageVector by lazy { lucide("ListChecks", "M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "m3 7 2 2 4-4") }
    val LockKeyhole: ImageVector by lazy { lucide("LockKeyhole", "M11.0 16.0 A1.0 1.0 0 1 0 13.0 16.0 A1.0 1.0 0 1 0 11.0 16.0", "M5.0 10.0 H19.0 Q21.0 10.0 21.0 12.0 V20.0 Q21.0 22.0 19.0 22.0 H5.0 Q3.0 22.0 3.0 20.0 V12.0 Q3.0 10.0 5.0 10.0 Z", "M7 10V7a5 5 0 0 1 10 0v3") }
    val MessageCircle: ImageVector by lazy { lucide("MessageCircle", "M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719") }
    val Minus: ImageVector by lazy { lucide("Minus", "M5 12h14") }
    val PenLine: ImageVector by lazy { lucide("PenLine", "M13 21h8", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z") }
    val Pencil: ImageVector by lazy { lucide("Pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4") }
    val Pilcrow: ImageVector by lazy { lucide("Pilcrow", "M13 4v16", "M17 4v16", "M19 4H9.5a4.5 4.5 0 0 0 0 9H13") }
    val Plus: ImageVector by lazy { lucide("Plus", "M5 12h14", "M12 5v14") }
    val Redo2: ImageVector by lazy { lucide("Redo2", "m15 14 5-5-5-5", "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13") }
    val RefreshCw: ImageVector by lazy { lucide("RefreshCw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }
    val Repeat2: ImageVector by lazy { lucide("Repeat2", "m2 9 3-3 3 3", "M13 18H7a2 2 0 0 1-2-2V6", "m22 15-3 3-3-3", "M11 6h6a2 2 0 0 1 2 2v10") }
    val Scissors: ImageVector by lazy { lucide("Scissors", "M3.0 6.0 A3.0 3.0 0 1 0 9.0 6.0 A3.0 3.0 0 1 0 3.0 6.0", "M8.12 8.12 12 12", "M20 4 8.12 15.88", "M3.0 18.0 A3.0 3.0 0 1 0 9.0 18.0 A3.0 3.0 0 1 0 3.0 18.0", "M14.8 14.8 20 20") }
    val Search: ImageVector by lazy { lucide("Search", "M3.0 11.0 A8.0 8.0 0 1 0 19.0 11.0 A8.0 8.0 0 1 0 3.0 11.0", "m21 21-4.3-4.3") }
    val Settings2: ImageVector by lazy { lucide("Settings2", "M20 7h-9", "M14 17H5", "M14.0 17.0 A3.0 3.0 0 1 0 20.0 17.0 A3.0 3.0 0 1 0 14.0 17.0", "M4.0 7.0 A3.0 3.0 0 1 0 10.0 7.0 A3.0 3.0 0 1 0 4.0 7.0") }
    val Share2: ImageVector by lazy { lucide("Share2", "M15.0 5.0 A3.0 3.0 0 1 0 21.0 5.0 A3.0 3.0 0 1 0 15.0 5.0", "M3.0 12.0 A3.0 3.0 0 1 0 9.0 12.0 A3.0 3.0 0 1 0 3.0 12.0", "M15.0 19.0 A3.0 3.0 0 1 0 21.0 19.0 A3.0 3.0 0 1 0 15.0 19.0", "M8.59 13.51 L15.42 17.49", "M15.41 6.51 L8.59 10.49") }
    val ShieldAlert: ImageVector by lazy { lucide("ShieldAlert", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "M12 8v4", "M12 16h.01") }
    val ShieldCheck: ImageVector by lazy { lucide("ShieldCheck", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4") }
    val Signal: ImageVector by lazy { lucide("Signal", "M2 20h.01", "M7 20v-4", "M12 20v-8", "M17 20V8", "M22 4v16") }
    val Smile: ImageVector by lazy { lucide("Smile", "M2.0 12.0 A10.0 10.0 0 1 0 22.0 12.0 A10.0 10.0 0 1 0 2.0 12.0", "M8 14s1.5 2 4 2 4-2 4-2", "M9 9 L9.01 9", "M15 9 L15.01 9") }
    val SquarePen: ImageVector by lazy { lucide("SquarePen", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z") }
    val Star: ImageVector by lazy { lucide("Star", "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z") }
    val Trash2: ImageVector by lazy { lucide("Trash2", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11 L10 17", "M14 11 L14 17") }
    val Undo2: ImageVector by lazy { lucide("Undo2", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11") }
    val Upload: ImageVector by lazy { lucide("Upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8 12 3 7 8", "M12 3 L12 15") }
    val User: ImageVector by lazy { lucide("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8.0 7.0 A4.0 4.0 0 1 0 16.0 7.0 A4.0 4.0 0 1 0 8.0 7.0") }
    val Wifi: ImageVector by lazy { lucide("Wifi", "M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0") }
    val X: ImageVector by lazy { lucide("X", "M18 6 6 18", "m6 6 12 12") }
    val MoreHorizontal get() = Ellipsis
    val Trash get() = Trash2
    val Edit get() = Pencil
    val Settings get() = Settings2
}
