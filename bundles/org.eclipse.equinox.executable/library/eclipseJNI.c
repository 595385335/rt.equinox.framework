/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 * 	   Andrew Niefer
 *******************************************************************************/
 
#include "eclipseJNI.h"
#include "eclipseCommon.h"
#include "eclipseOS.h"
#include "eclipseShm.h"

#include <stdlib.h>
#include <string.h>

static JNINativeMethod natives[] = {{"_update_splash", "()V", (void *)&update_splash},
									{"_get_splash_handle", "()J", (void *)&get_splash_handle},
									{"_set_exit_data", "(Ljava/lang/String;Ljava/lang/String;)V", (void *)&set_exit_data},
									{"_show_splash", "(Ljava/lang/String;)V", (void *)&show_splash},
									{"_takedown_splash", "()V", (void *)&takedown_splash}};
  
#ifdef UNICODE
#define setExitData setExitDataW
#elif WIN32
extern void setExitDataW(JNIEnv *env, jstring id, jstring s);
#endif

/* local methods */
static jstring newJavaString(JNIEnv *env, _TCHAR * str);
static void splash(JNIEnv *env, jstring s);
static void registerNatives(JNIEnv *env);
static int shouldShutdown(JNIEnv *env);
static void JNI_ReleaseStringChars(JNIEnv *env, jstring s, const _TCHAR* data);
static const _TCHAR* JNI_GetStringChars(JNIEnv *env, jstring str);

void setExitData(JNIEnv *env, jstring id, jstring s);

static JavaVM * jvm = 0;
static JNIEnv *env = 0;

/* JNI Methods                                 
 * we only want one version of the JNI functions 
 * Because there are potentially ANSI and UNICODE versions of everything, we need to be
 * able to call out to either, so we will set hooks depending on which version of 
 * registerNatives gets called.
 */
#if (!defined(UNICODE) || defined(VISTA))
void (* exitDataHook)(JNIEnv *env, jstring id, jstring s);
void (* dispatchHook)();
jlong (* splashHandleHook)();
void (* showSplashHook)(JNIEnv *env, jstring s);
void (* takeDownHook)();
#else
extern void (* exitDataHook)(JNIEnv *env, jstring id, jstring s);
extern void (* dispatchHook)();
extern jlong (* splashHandleHook)();
extern void (* showSplashHook)(JNIEnv *env, jstring s);
extern void (* takeDownHook)();
#endif

#if (!defined(UNICODE) || defined(VISTA)) 
/* JNI Callback methods */
JNIEXPORT void JNICALL set_exit_data(JNIEnv * env, jobject obj, jstring id, jstring s){
	if(exitDataHook != NULL)
		exitDataHook(env, id, s);
	else /* hook was not set, just call the ANSI version */
#if (defined(_WIN32) || defined(VISTA))
		setExitDataW(env, id, s);
#else
		setExitData(env, id, s);
#endif
}

JNIEXPORT void JNICALL update_splash(JNIEnv * env, jobject obj){
	if(dispatchHook != NULL)
		dispatchHook();
	else
		dispatchMessages();
}

JNIEXPORT jlong JNICALL get_splash_handle(JNIEnv * env, jobject obj){
	if(splashHandleHook != NULL)
		return splashHandleHook();
	else
		return getSplashHandle();
}

JNIEXPORT void JNICALL show_splash(JNIEnv * env, jobject obj, jstring s){
	if(showSplashHook != NULL)
		showSplashHook(env, s);
	else
		splash(env, s);	
}

JNIEXPORT void JNICALL takedown_splash(JNIEnv * env, jobject obj){
	if(takeDownHook != NULL)
		takeDownHook();
	else
		takeDownSplash();
}
#endif

static void registerNatives(JNIEnv *env) {
	jclass bridge = (*env)->FindClass(env, "org/eclipse/equinox/launcher/JNIBridge");
	if(bridge != NULL) {
		int numNatives = sizeof(natives) / sizeof(natives[0]);
		(*env)->RegisterNatives(env, bridge, natives, numNatives);	
	}
	if( (*env)->ExceptionOccurred(env) != 0 ){
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	/*set hooks*/
	splashHandleHook = &getSplashHandle;
	exitDataHook = &setExitData;
	dispatchHook = &dispatchMessages;
	showSplashHook = &splash;
	takeDownHook = &takeDownSplash;
}

static void splash(JNIEnv *env, jstring s) {
	const _TCHAR* data = NULL;
	if(s != NULL) {
		data = JNI_GetStringChars(env, s);
		if(data != NULL) {
			showSplash(data);
			JNI_ReleaseStringChars(env, s, data);
		} else {
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
	}
}

void setExitData(JNIEnv *env, jstring id, jstring s){
	const _TCHAR* data = NULL;
	const _TCHAR* sharedId = NULL;
	size_t length;
	 
	if(s != NULL) {
		length = (*env)->GetStringLength(env, s);
		if(!(*env)->ExceptionOccurred(env)) {
			data = JNI_GetStringChars(env, s);
			if (data != NULL) {
				if(id != NULL) {
					sharedId = JNI_GetStringChars(env, id);
					if(sharedId != NULL) {
						setSharedData(sharedId, data);
						JNI_ReleaseStringChars(env, id, sharedId);
					}
				} else {
					exitData = malloc((length + 1) * sizeof(_TCHAR*));
					_tcsncpy( exitData, data, length);
					exitData[length] = 0;
				}
				JNI_ReleaseStringChars(env, s, data);
			}
		}
		if(data == NULL && sharedId == NULL) {
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
	}
}

/* Get a _TCHAR* from a jstring, string should be released later with JNI_ReleaseStringChars */
static const _TCHAR * JNI_GetStringChars(JNIEnv *env, jstring str) {
	const _TCHAR * result = NULL;
#ifdef UNICODE
	result = (*env)->GetStringChars(env, str, 0);
#else
	_TCHAR* buffer = NULL;
	jclass stringClass = (*env)->FindClass(env, "java/lang/String");
	if (stringClass != NULL) {
		jmethodID getBytesMethod = (*env)->GetMethodID(env, stringClass, "getBytes", "()[B");
		if (getBytesMethod != NULL) {
			jbyteArray bytes = (*env)->CallObjectMethod(env, str, getBytesMethod);
			if (!(*env)->ExceptionOccurred(env)) {
				jsize length = (*env)->GetArrayLength(env, bytes);
				buffer = malloc( (length + 1) * sizeof(_TCHAR*));
				(*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte*)buffer);
				buffer[length] = 0;
			}
			(*env)->DeleteLocalRef(env, bytes);
		}
	}
	if(buffer == NULL) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	result = buffer;
#endif
	return result;
}

/* Release the string that was obtained using JNI_GetStringChars */
static void JNI_ReleaseStringChars(JNIEnv *env, jstring s, const _TCHAR* data) {
#ifdef UNICODE
	(*env)->ReleaseStringChars(env, s, data);
#else
	free((_TCHAR*)data);
#endif
}

static jstring newJavaString(JNIEnv *env, _TCHAR * str)
{
	jstring newString = NULL;
	size_t length = _tcslen(str);
	
#ifdef UNICODE
	newString = (*env)->NewString(env, str, length);
#else
	jbyteArray bytes = (*env)->NewByteArray(env, length);
	if(bytes != NULL) {
		(*env)->SetByteArrayRegion(env, bytes, 0, length, str);
		if (!(*env)->ExceptionOccurred(env)) {
			jclass stringClass = (*env)->FindClass(env, "java/lang/String");
			if(stringClass != NULL) {
				jmethodID ctor = (*env)->GetMethodID(env, stringClass, "<init>",  "([B)V");
				if(ctor != NULL) {
					newString = (*env)->NewObject(env, stringClass, ctor, bytes);
				}
			}
		}
		(*env)->DeleteLocalRef(env, bytes);
	}
#endif
	if(newString == NULL) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	return newString;
}

static jobjectArray createRunArgs( JNIEnv *env, _TCHAR * args[] ) {
	int index = 0, length = -1;
	jclass stringClass;
	jobjectArray stringArray = NULL;
	jstring string;
	
	/*count the number of elements first*/
	while(args[++length] != NULL);
	
	stringClass = (*env)->FindClass(env, "java/lang/String");
	if(stringClass != NULL) {
		stringArray = (*env)->NewObjectArray(env, length, stringClass, 0);
		if(stringArray != NULL) {
			for( index = 0; index < length; index++) {
				string = newJavaString(env, args[index]);
				if(string != NULL) {
					(*env)->SetObjectArrayElement(env, stringArray, index, string); 
					(*env)->DeleteLocalRef(env, string);
				} else {
					(*env)->DeleteLocalRef(env, stringArray);
					(*env)->ExceptionDescribe(env);
					(*env)->ExceptionClear(env);
					return NULL;
				}
			}
		}
	} 
	if(stringArray == NULL) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	return stringArray;
}
					 
int startJavaJNI( _TCHAR* libPath, _TCHAR* vmArgs[], _TCHAR* progArgs[] )
{
	int i;
	int numVMArgs = -1;
	int jvmExitCode = -1;
	void * jniLibrary;
	JNI_createJavaVM createJavaVM;
	JavaVMInitArgs init_args;
	JavaVMOption * options;
	
	/* JNI reflection */
	jclass mainClass = NULL;			/* The Main class to load */
	jmethodID mainConstructor = NULL;	/* Main's default constructor Main() */
	jobject mainObject = NULL;			/* An instantiation of the main class */
	jmethodID runMethod = NULL;			/* Main.run(String[]) */
	jobjectArray methodArgs = NULL;		/* Arguments to pass to run */
	
	jniLibrary = loadLibrary(libPath);
	if(jniLibrary == NULL) {
		return -1; /*error*/
	}

	createJavaVM = (JNI_createJavaVM)findSymbol(jniLibrary, _T_ECLIPSE("JNI_CreateJavaVM"));
	if(createJavaVM == NULL) {
		return -1; /*error*/
	}
	
	/* count the vm args */
	while(vmArgs[++numVMArgs] != NULL) {}
	
	if(numVMArgs <= 0) {
		/*error, we expect at least the required vm arg */
		return -1;
	}
	
	options = malloc(numVMArgs * sizeof(JavaVMOption));
	for(i = 0; i < numVMArgs; i++){
		options[i].optionString = toNarrow(vmArgs[i]);
		options[i].extraInfo = 0;
	}
		
#ifdef MACOSX
	init_args.version = JNI_VERSION_1_4;
#else		
	init_args.version = JNI_VERSION_1_2;
#endif
	init_args.options = options;
	init_args.nOptions = numVMArgs;
	init_args.ignoreUnrecognized = JNI_TRUE;
	
	if( createJavaVM(&jvm, &env, &init_args) == 0 ) {
		registerNatives(env);
		
		mainClass = (*env)->FindClass(env, "org/eclipse/equinox/launcher/Main");
		if(mainClass != NULL) {
			mainConstructor = (*env)->GetMethodID(env, mainClass, "<init>", "()V");
			if(mainConstructor != NULL) {
				mainObject = (*env)->NewObject(env, mainClass, mainConstructor);
				if(mainObject != NULL) {
					runMethod = (*env)->GetMethodID(env, mainClass, "run", "([Ljava/lang/String;)I");
					if(runMethod != NULL) {
						methodArgs = createRunArgs(env, progArgs);
						if(methodArgs != NULL) {
							jvmExitCode = (*env)->CallIntMethod(env, mainObject, runMethod, methodArgs);
							(*env)->DeleteLocalRef(env, methodArgs);
						}
					}
					(*env)->DeleteLocalRef(env, mainObject);
				}
			}
		} 
		if((*env)->ExceptionOccurred(env)){
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
		
	}

	/* toNarrow allocated new strings, free them */
	for(i = 0; i < numVMArgs; i++){
		free( options[i].optionString );
	}
	free(options);
	return jvmExitCode;
}

void cleanupVM(int exitCode) {
	JNIEnv * localEnv = env;
	if (jvm == 0)
		return;
	
	if (secondThread)
		(*jvm)->AttachCurrentThread(jvm, (void**)&localEnv, NULL);
	else
		localEnv = env;
	if (localEnv == 0)
		return;
	
	/* we call System.exit() unless osgi.noShutdown is set */
	if (shouldShutdown(env)) {
		jclass systemClass = NULL;
		jmethodID exitMethod = NULL;
		systemClass = (*env)->FindClass(env, "java/lang/System");
		if (systemClass != NULL) {
			exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
			if (exitMethod != NULL) {
				(*env)->CallStaticVoidMethod(env, systemClass, exitMethod, exitCode);
			}
		}
		if ((*env)->ExceptionOccurred(env)) {
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
	}
	(*jvm)->DestroyJavaVM(jvm);
}

static int shouldShutdown(JNIEnv * env) {
	jclass booleanClass = NULL;
	jmethodID method = NULL;
	jstring arg = NULL;
	jboolean result = 0;
	
	booleanClass = (*env)->FindClass(env, "java/lang/Boolean");
	if (booleanClass != NULL) {
		method = (*env)->GetStaticMethodID(env, booleanClass, "getBoolean", "(Ljava/lang/String;)Z");
		if (method != NULL) {
			arg = newJavaString(env, _T_ECLIPSE("osgi.noShutdown"));
			result = (*env)->CallStaticBooleanMethod(env, booleanClass, method, arg);
			(*env)->DeleteLocalRef(env, arg);
		}
	}
	if ((*env)->ExceptionOccurred(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	return (result == 0);
}


