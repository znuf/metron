/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.management;

import com.jakewharton.fliptables.FlipTable;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.text.WordUtils;
import org.apache.log4j.Logger;
import org.apache.metron.common.dsl.*;
import org.apache.metron.common.stellar.shell.PausableInput;
import org.apache.metron.common.stellar.shell.StellarExecutor;
import org.apache.metron.common.utils.ConversionUtils;
import org.jboss.aesh.console.AeshProcess;
import org.jboss.aesh.console.Console;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.*;

import static org.apache.metron.common.stellar.shell.StellarExecutor.CONSOLE;

public class ShellFunctions {
  private static final Logger LOG = Logger.getLogger(ShellFunctions.class);

  @Stellar(
           namespace = "SHELL"
          ,name = "MAP2TABLE"
          ,description = "Take a map and return a table"
          ,params = {"map - Map"
                    }
          ,returns = "The map in table form"
          )
  public static class Map2Table extends BaseStellarFunction {

    @Override
    public Object apply(List<Object> args) {
      if(args.size() < 1) {
        return null;
      }
      Map<Object, Object> map = (Map<Object, Object>) args.get(0);
      if(map == null) {
        map = new HashMap<>();
      }
      String[] headers = {"KEY", "VALUE"};
      String[][] data = new String[map.size()][2];
      int i = 0;
      for(Map.Entry<Object, Object> kv : map.entrySet()) {
        data[i++] = new String[] {kv.getKey().toString(), kv.getValue().toString()};
      }
      return FlipTable.of(headers, data);
    }
  }

  @Stellar(
           namespace = "SHELL"
          ,name = "LIST_VARS"
          ,description = "Return the variables in a tabular form"
          ,params = {
             "wrap : Length of string to wrap the columns"
                    }
          ,returns = "A tabular representation of the variables."
          )
  public static class ListVars implements StellarFunction {

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {

      Map<String, StellarExecutor.VariableResult> variables = (Map<String, StellarExecutor.VariableResult>) context.getCapability(StellarExecutor.SHELL_VARIABLES).get();
      String[] headers = {"VARIABLE", "VALUE", "EXPRESSION"};
      String[][] data = new String[variables.size()][3];
      int wordWrap = -1;
      if(args.size() > 0) {
        wordWrap = ConversionUtils.convert(args.get(0), Integer.class);
      }
      int i = 0;
      for(Map.Entry<String, StellarExecutor.VariableResult> kv : variables.entrySet()) {
        StellarExecutor.VariableResult result = kv.getValue();
        data[i++] = new String[] { toWrappedString(kv.getKey().toString(), wordWrap)
                                 , toWrappedString(result.getResult(), wordWrap)
                                 , toWrappedString(result.getExpression(), wordWrap)
                                 };
      }
      return FlipTable.of(headers, data);
    }

    private static String toWrappedString(Object o, int wrap) {
      String s = "" + o;
      if(wrap <= 0) {
        return s;
      }
      return WordUtils.wrap(s, wrap);
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }

  @Stellar(
           namespace = "SHELL"
          ,name = "VARS2MAP"
          ,description = "Take a set of variables and return a map"
          ,params = {"variables* - variable names to use to create map "
                    }
          ,returns = "A map associating the variable name with the stellar expression."
          )
  public static class Var2Map implements StellarFunction {

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {
      Map<String, StellarExecutor.VariableResult> variables = (Map<String, StellarExecutor.VariableResult>) context.getCapability(StellarExecutor.SHELL_VARIABLES).get();
      LinkedHashMap<String, String> ret = new LinkedHashMap<>();
      for(Object arg : args) {
        if(arg == null) {
          continue;
        }
        String variable = (String)arg;
        StellarExecutor.VariableResult result = variables.get(variable);
        if(result != null && result.getExpression() != null) {
          ret.put(variable, result.getExpression());
        }
      }
      return ret;
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }

  @Stellar(
           namespace = "SHELL"
          ,name = "GET_EXPRESSION"
          ,description = "Get a stellar expression from a variable"
          ,params = {"variable - variable name"
                    }
          ,returns = "The stellar expression associated with the variable."
          )
  public static class GetExpression implements StellarFunction {

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {
      Map<String, StellarExecutor.VariableResult> variables = (Map<String, StellarExecutor.VariableResult>) context.getCapability(StellarExecutor.SHELL_VARIABLES).get();
      if(args.size() == 0) {
        return null;
      }
      String variable = (String) args.get(0);
      if(variable == null) {
        return null;
      }
      StellarExecutor.VariableResult result = variables.get(variable);
      if(result != null && result.getExpression() != null) {
        return result.getExpression();
      }
      return null;
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }

  @Stellar(
           namespace = "SHELL"
          ,name = "EDIT"
          ,description = "Open an editor (optionally initialized with text) and return " +
                         "whatever is saved from the editor.  The editor to use is pulled " +
                         "from `EDITOR` or `VISUAL` environment variable."
          ,params = {   "string - (Optional) A string whose content is used to initialize the editor."
                    }
          ,returns = "The content that the editor saved after editor exit."
          )
  public static class Edit implements StellarFunction {

    private String getEditor() {
      // if we have editor in the system properties, it should
      // override the env so we check that first
      String editor = System.getProperty("EDITOR");
      if(org.apache.commons.lang3.StringUtils.isEmpty(editor)) {
        editor = System.getenv().get("EDITOR");
      }
      if(org.apache.commons.lang3.StringUtils.isEmpty(editor)) {
        editor = System.getenv("VISUAL");
      }
      if(org.apache.commons.lang3.StringUtils.isEmpty(editor)) {
        editor = "/bin/vi";
      }
      return editor;
    }

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {
      File outFile = null;
      String editor = getEditor();
      try {
        outFile = File.createTempFile("stellar_shell", "out");
        if(args.size() > 0) {
          String arg = (String)args.get(0);
          try(PrintWriter pw = new PrintWriter(outFile)) {
            IOUtils.write(arg, pw);
          }
        }
      } catch (IOException e) {
        String message = "Unable to create temp file: " + e.getMessage();
        LOG.error(message, e);
        throw new IllegalStateException(message, e);
      }
      Optional<Object> console =  context.getCapability(CONSOLE, false);
      try {
        PausableInput.INSTANCE.pause();
        //shut down the IO for the console
        ProcessBuilder processBuilder = new ProcessBuilder(editor, outFile.getAbsolutePath());
        processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
          Process p = processBuilder.start();
          // wait for termination.
          p.waitFor();
          try (BufferedReader br = new BufferedReader(new FileReader(outFile))) {
            String ret = IOUtils.toString(br).trim();
            return ret;
          }
        } catch (Exception e) {
          String message = "Unable to read output: " + e.getMessage();
          LOG.error(message, e);
          return null;
        }
      } finally {
        try {
          PausableInput.INSTANCE.unpause();
          if(console.isPresent()) {
            ((Console)console.get()).pushToInputStream("\b\n");
          }
        } catch (IOException e) {
          LOG.error("Unable to unpause: " + e.getMessage(), e);
        }
        if(outFile.exists()) {
          outFile.delete();
        }
      }
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }
}
