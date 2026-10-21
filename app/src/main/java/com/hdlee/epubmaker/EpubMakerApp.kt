package com.hdlee.epubmaker

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class EpubMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox 가 글꼴/리소스 파일을 읽을 수 있도록 초기화
        PDFBoxResourceLoader.init(applicationContext)
    }
}
