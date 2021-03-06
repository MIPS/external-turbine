/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.binder;

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bound.ModuleInfo.ExportInfo;
import com.google.turbine.binder.bound.ModuleInfo.OpenInfo;
import com.google.turbine.binder.bound.ModuleInfo.ProvideInfo;
import com.google.turbine.binder.bound.ModuleInfo.RequireInfo;
import com.google.turbine.binder.bound.ModuleInfo.UseInfo;
import com.google.turbine.binder.bound.PackageSourceBoundModule;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ModDirective;
import com.google.turbine.tree.Tree.ModExports;
import com.google.turbine.tree.Tree.ModOpens;
import com.google.turbine.tree.Tree.ModProvides;
import com.google.turbine.tree.Tree.ModRequires;
import com.google.turbine.tree.Tree.ModUses;
import com.google.turbine.tree.TurbineModifier;
import com.google.turbine.type.AnnoInfo;

/** Binding pass for modules. */
public class ModuleBinder {

  public static ModuleInfo bind(
      PackageSourceBoundModule module,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      Env<ModuleSymbol, ModuleInfo> moduleEnv,
      Optional<String> moduleVersion) {
    return new ModuleBinder(module, env, moduleEnv, moduleVersion).bind();
  }

  private final PackageSourceBoundModule module;
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;
  private final Env<ModuleSymbol, ModuleInfo> moduleEnv;
  private final Optional<String> moduleVersion;
  private final CompoundScope scope;

  public ModuleBinder(
      PackageSourceBoundModule module,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      Env<ModuleSymbol, ModuleInfo> moduleEnv,
      Optional<String> moduleVersion) {
    this.module = module;
    this.env = env;
    this.moduleEnv = moduleEnv;
    this.moduleVersion = moduleVersion;
    this.scope = module.scope().toScope(Resolve.resolveFunction(env, /* origin= */ null));
  }

  private ModuleInfo bind() {
    // bind annotations; constant fields are already bound
    ConstEvaluator constEvaluator =
        new ConstEvaluator(
            /* origin= */ null,
            /* owner= */ null,
            module.memberImports(),
            module.source(),
            scope,
            /* values= */ new SimpleEnv<>(ImmutableMap.of()),
            env);
    ImmutableList.Builder<AnnoInfo> annoInfos = ImmutableList.builder();
    for (Tree.Anno annoTree : module.module().annos()) {
      ClassSymbol sym = resolve(annoTree.position(), annoTree.name());
      annoInfos.add(new AnnoInfo(module.source(), sym, annoTree, null));
    }
    ImmutableList<AnnoInfo> annos = constEvaluator.evaluateAnnotations(annoInfos.build());

    int flags = module.module().open() ? TurbineFlag.ACC_OPEN : 0;

    // bind directives
    ImmutableList.Builder<ModuleInfo.RequireInfo> requires = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.ExportInfo> exports = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.OpenInfo> opens = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.UseInfo> uses = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.ProvideInfo> provides = ImmutableList.builder();
    boolean requiresJavaBase = false;
    for (ModDirective directive : module.module().directives()) {
      switch (directive.directiveKind()) {
        case REQUIRES:
          {
            ModRequires require = (ModRequires) directive;
            requiresJavaBase |= require.moduleName().equals(ModuleSymbol.JAVA_BASE.name());
            requires.add(bindRequires(require));
            break;
          }
        case EXPORTS:
          exports.add(bindExports((ModExports) directive));
          break;
        case OPENS:
          opens.add(bindOpens((ModOpens) directive));
          break;
        case USES:
          uses.add(bindUses((ModUses) directive));
          break;
        case PROVIDES:
          provides.add(bindProvides((ModProvides) directive));
          break;
        default:
          throw new AssertionError(directive.kind());
      }
    }
    if (!requiresJavaBase) {
      // everything requires java.base, either explicitly or implicitly
      ModuleInfo javaBaseModule = moduleEnv.get(ModuleSymbol.JAVA_BASE);
      verifyNotNull(javaBaseModule, ModuleSymbol.JAVA_BASE.name());
      requires =
          ImmutableList.<RequireInfo>builder()
              .add(
                  new RequireInfo(
                      ModuleSymbol.JAVA_BASE.name(),
                      TurbineFlag.ACC_MANDATED,
                      javaBaseModule.version()))
              .addAll(requires.build());
    }

    return new ModuleInfo(
        module.module().moduleName(),
        moduleVersion.orNull(),
        flags,
        annos,
        requires.build(),
        exports.build(),
        opens.build(),
        uses.build(),
        provides.build());
  }

  private RequireInfo bindRequires(ModRequires directive) {
    String moduleName = directive.moduleName();
    int flags = 0;
    for (TurbineModifier mod : directive.mods()) {
      switch (mod) {
        case TRANSITIVE:
          flags |= mod.flag();
          break;
        case STATIC:
          // the 'static' modifier on requires translates to ACC_STATIC_PHASE, not ACC_STATIC
          flags |= TurbineFlag.ACC_STATIC_PHASE;
          break;
        default:
          throw new AssertionError(mod);
      }
    }
    ModuleInfo requires = moduleEnv.get(new ModuleSymbol(moduleName));
    return new RequireInfo(moduleName, flags, requires != null ? requires.version() : null);
  }

  private ExportInfo bindExports(ModExports directive) {
    return new ExportInfo(directive.packageName(), directive.moduleNames());
  }

  private OpenInfo bindOpens(ModOpens directive) {
    return new OpenInfo(directive.packageName(), directive.moduleNames());
  }

  private UseInfo bindUses(ModUses directive) {
    return new UseInfo(resolve(directive.position(), directive.typeName()));
  }

  private ProvideInfo bindProvides(ModProvides directive) {
    ClassSymbol sym = resolve(directive.position(), directive.typeName());
    ImmutableList.Builder<ClassSymbol> impls = ImmutableList.builder();
    for (ImmutableList<String> impl : directive.implNames()) {
      impls.add(resolve(directive.position(), impl));
    }
    return new ProvideInfo(sym, impls.build());
  }

  /* Resolves qualified class names. */
  private ClassSymbol resolve(int pos, ImmutableList<String> simpleNames) {
    LookupKey key = new LookupKey(simpleNames);
    LookupResult result = scope.lookup(key);
    if (result == null) {
      throw error(ErrorKind.SYMBOL_NOT_FOUND, pos, Joiner.on('.').join(simpleNames));
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (String name : result.remaining()) {
      sym = Resolve.resolve(env, /* origin= */ null, sym, name);
      if (sym == null) {
        throw error(ErrorKind.SYMBOL_NOT_FOUND, pos, name);
      }
    }
    return sym;
  }

  private TurbineError error(ErrorKind kind, int pos, Object... args) {
    return TurbineError.format(module.source(), pos, kind, args);
  }
}
