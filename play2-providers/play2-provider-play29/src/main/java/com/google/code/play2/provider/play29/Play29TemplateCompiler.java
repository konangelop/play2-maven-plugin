/*
 * Copyright 2013-2020 Grzegorz Slowikowski (gslowikowski at gmail dot com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.google.code.play2.provider.play29;

import java.io.File;
import java.util.Collections;
import java.util.List;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;
import scala.collection.immutable.Seq;
import scala.io.Codec;

import play.twirl.compiler.TwirlCompiler;
import play.twirl.compiler.TemplateCompilationError;

import com.google.code.play2.provider.api.Play2TemplateCompiler;
import com.google.code.play2.provider.api.TemplateCompilationException;

public class Play29TemplateCompiler
    implements Play2TemplateCompiler
{
    private static final String[] templateExts = { "html", "txt", "xml", "js" };

    private static final String[] defaultJavaImports =
        new String[] {
            "models._",
            "controllers._",
            "play.api.i18n._",
            "views.%format%._",
            "play.api.templates.PlayMagic._",
            "play.api.mvc.Call",
            "java.lang._",
            "java.util._",
            "play.core.j.PlayMagicForJava._",
            "play.mvc._",
            "play.api.data.Field",
            "play.data._",
            "play.core.j.PlayFormsMagicForJava._" };

    private static final String[] defaultScalaImports =
        new String[] {
            "models._",
            "controllers._",
            "play.api.i18n._",
            "views.%format%._",
            "play.api.templates.PlayMagic._",
            "play.api.mvc.Call" };

    private File sourceDirectory;

    private File outputDirectory;

    private List<String> additionalImports = Collections.emptyList();

    @Override
    public String[] getDefaultJavaImports()
    {
        return defaultJavaImports;
    }

    @Override
    public String[] getDefaultScalaImports()
    {
        return defaultScalaImports;
    }

    @Override
    public String[] getTemplateFileExtensions()
    {
        return templateExts;
    }

    @Override
    public void setSourceDirectory( File sourceDirectory )
    {
        this.sourceDirectory = sourceDirectory;
    }

    @Override
    public void setOutputDirectory( File outputDirectory )
    {
        this.outputDirectory = outputDirectory;
    }

    @Override
    public void setAdditionalImports( List<String> additionalImports )
    {
        this.additionalImports = additionalImports;
    }

    @Override
    public File compile( File templateFile )
        throws TemplateCompilationException
    {
        File result = null;

        String fileName = templateFile.getName();
        String ext = fileName.substring( fileName.lastIndexOf( '.' ) + 1 );
        String templateName = fileName.substring( 0, fileName.lastIndexOf( '.' ) );
        String resultFileName = templateName.replace( '.', '_' ) + ".template.scala";
        File resultDir = getResultDir( templateFile );

        Seq<String> scalaAdditionalImports = CollectionConverters.asScala( additionalImports ).toSeq();

        try
        {
            Option<File> resultFile =
                TwirlCompiler.compile( templateFile, sourceDirectory, outputDirectory, ext,
                                       scalaAdditionalImports, CollectionConverters.asScala( Collections.<String>emptyList() ).toSeq(), Codec.UTF8(),
                                       false );
            if ( resultFile.isDefined() )
            {
                result = resultFile.get();
            }
            else
            {
                result = new File( resultDir, resultFileName );
            }
        }
        catch ( TemplateCompilationError e )
        {
            throw new TemplateCompilationException( e.source(), e.message(), e.line(), e.column() );
        }
        return result;
    }

    private File getResultDir( File templateFile )
    {
        File resultDir = outputDirectory;

        File parentDir = templateFile.getParentFile();
        if ( !parentDir.equals( sourceDirectory ) )
        {
            String relativePath = parentDir.getAbsolutePath().substring( sourceDirectory.getAbsolutePath().length() );
            resultDir = new File( outputDirectory, relativePath );
        }
        return resultDir;
    }

}
