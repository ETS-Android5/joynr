package io.joynr.generator.cpp.communicationmodel
/*
 * !!!
 *
 * Copyright (C) 2011 - 2015 BMW Car IT GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.inject.Inject
import io.joynr.generator.cpp.util.JoynrCppGeneratorExtensions
import io.joynr.generator.cpp.util.TemplateBase
import org.franca.core.franca.FCompoundType
import io.joynr.generator.util.CompoundTypeTemplate

class TypeCppTemplate implements CompoundTypeTemplate{

	@Inject
	private extension TemplateBase

	@Inject
	private extension JoynrCppGeneratorExtensions

	override generate(FCompoundType type) {
		val typeName = type.joynrName
		'''
		«warning»

		#include "«getPackagePathWithJoynrPrefix(type, "/")»/«typeName».h"
		#include "joynr/Reply.h"
		#include "joynr/DeclareMetatypeUtil.h"
		#include "qjson/serializer.h"
		#include <QMetaEnum>
		#include <QDateTime>

		«getNamespaceStarter(type)»

		void «typeName»::registerMetatypes() {
			qRegisterMetaType<«getMappedDatatype(type)»>("«getMappedDatatype(type)»");
			«FOR complexMember: getComplexMembers(type)»
				qRegisterMetaType<«getMappedDatatype(complexMember)»>("«getMappedDatatype(complexMember)»");
				qRegisterMetaType<«getMappedDatatype(complexMember).replace('::','__')»>("«getMappedDatatype(complexMember).replace('::','__')»");
			«ENDFOR»
			«FOR enumMember: getEnumMembers(type)»
				{
					qRegisterMetaType<«getEnumContainer(enumMember.type.derived)»>();
					int id = qRegisterMetaType<«getMappedDatatype(enumMember)»>();
					QJson::Serializer::registerEnum(id, «getEnumContainer(enumMember.type.derived)»::staticMetaObject.enumerator(0));
				}
			«ENDFOR»
		}

		«typeName»::«typeName»() :
			«IF hasExtendsDeclaration(type)»
				«getExtendedType(type).joynrName»()«IF !getMembers(type).empty»,«ENDIF»
			«ELSE»
				QObject()«IF !getMembers(type).empty»,«ENDIF»
			«ENDIF»
			«FOR member: getMembers(type) SEPARATOR ','»
				m_«member.joynrName»(«getDefaultValue(member)»)
			«ENDFOR»
		{
			registerMetatypes();
		}

		«IF !getMembersRecursive(type).empty»
		«typeName»::«typeName»(
			«FOR member: getMembersRecursive(type) SEPARATOR ','»
				«getMappedDatatypeOrList(member)» new_«member.joynrName»
			«ENDFOR»
			):
			«IF hasExtendsDeclaration(type)»
				«val extendedType = getExtendedType(type)»
				«extendedType.joynrName»(
				«FOR member: getMembersRecursive(extendedType) SEPARATOR ','»
					new_«member.joynrName»
				«ENDFOR»
				)«IF !getMembers(type).empty»,«ENDIF»
			«ELSE»
				QObject()«IF !getMembers(type).empty»,«ENDIF»
			«ENDIF»
			«FOR member: getMembers(type) SEPARATOR ','»
				m_«member.joynrName»(new_«member.joynrName»)
			«ENDFOR»
		{
			registerMetatypes();
		}
		«ENDIF»

		//CopyConstructor
		«typeName»::«typeName»(const «typeName»& other) :
			«IF hasExtendsDeclaration(type)»
				«getExtendedType(type).joynrName»(other),
			«ELSE»
				QObject()«IF !getMembers(type).empty»,«ENDIF»
			«ENDIF»
			«FOR member: getMembers(type) SEPARATOR ','»
				m_«member.joynrName»(other.m_«member.joynrName»)
			«ENDFOR»
		{
			«IF getMembersRecursive(type).empty»
			Q_UNUSED(other);
			«ENDIF»
			registerMetatypes();
		}

		«typeName»::~«typeName»() {
		}

		«FOR member: getMembers(type)»
			«val joynrName = member.joynrName»
			«IF isArray(member)»
				QList<QVariant> «typeName»::get«joynrName.toFirstUpper»Internal() const {
					QList<QVariant> returnList;
					returnList.reserve( this->m_«joynrName».size() );
					«getMappedDatatypeOrList(member)»::const_iterator iter = this->m_«joynrName».begin();
					while(iter!=this->m_«joynrName».end()) {
						QVariant value;
						«IF isEnum(member.type)»
							value.setValue((int)*iter);
						«ELSE»
							value.setValue(*iter);
						«ENDIF»
						returnList.push_back(value);
						iter++;
					}
					return returnList;
				}

				void «typeName»::set«joynrName.toFirstUpper»Internal(const QList<QVariant>& obj«joynrName.toFirstUpper») {
					this->m_«joynrName».clear();
					this->m_«joynrName».reserve( obj«joynrName.toFirstUpper».size() );
					QList<QVariant>::const_iterator iter = obj«joynrName.toFirstUpper».begin();
					while(iter!=obj«joynrName.toFirstUpper».end()){
						«IF isEnum(member.type)»
							this->m_«joynrName».push_back((«getMappedDatatype(member)»)(*iter).value<int>());
						«ELSE»
							this->m_«joynrName».push_back((*iter).value<«getMappedDatatype(member)»>());
						«ENDIF»
						iter++;
					}
				}

			«ELSEIF isByteBuffer(member.type)»
				QByteArray «typeName»::get«joynrName.toFirstUpper»Internal() const {
					return m_«joynrName».toBase64();
				}

				void «typeName»::set«joynrName.toFirstUpper»Internal(const QByteArray& obj«joynrName.toFirstUpper») {
					m_«joynrName» = QByteArray::fromBase64(obj«joynrName.toFirstUpper»);
				}

			«ELSE»
				«IF isEnum(member.type)»
					QString «typeName»::get«joynrName.toFirstUpper»Internal() const {
						QMetaEnum metaEnum = «getMappedDatatypeOrList(member).substring(0, getMappedDatatypeOrList(member).length-6)»::staticMetaObject.enumerator(0);
						return metaEnum.valueToKey(this->m_«joynrName»);
					}

					void «typeName»::set«joynrName.toFirstUpper»Internal(const QString& obj«joynrName.toFirstUpper») {
						QMetaEnum metaEnum = «getMappedDatatypeOrList(member).substring(0, getMappedDatatypeOrList(member).length-6)»::staticMetaObject.enumerator(0);
						int value = metaEnum.keyToValue(obj«joynrName.toFirstUpper».toStdString().c_str());
						this->m_«joynrName» = («getMappedDatatypeOrList(member)»)value;
					}

				«ENDIF»
			«ENDIF»
			«getMappedDatatypeOrList(member)» «typeName»::get«joynrName.toFirstUpper»() const {
				return m_«joynrName»;
			}

			void «typeName»::set«joynrName.toFirstUpper»(const «getMappedDatatypeOrList(member)»& obj«joynrName.toFirstUpper») {
				this->m_«joynrName» = obj«joynrName.toFirstUpper»;
			}
		«ENDFOR»

		//AssignOperator
		«typeName»& «typeName»::operator=(const «typeName»& other) {
			«IF getMembersRecursive(type).empty»
			Q_UNUSED(other);
			«ENDIF»
			«IF hasExtendsDeclaration(type)»
				«val base = getExtendedType(type)»
				«getMappedDatatype(base)»::operator=(other);
			«ENDIF»
			«FOR member: getMembers(type)»
				this->m_«member.joynrName» = other.m_«member.joynrName»;
			«ENDFOR»
			return *this;
		}

		bool «typeName»::operator==(const «typeName»& other) const {
			«IF getMembersRecursive(type).empty»
			Q_UNUSED(other);
			«ENDIF»
			return
				«FOR member: getMembers(type)»
					this->m_«member.joynrName» == other.m_«member.joynrName» &&
				«ENDFOR»
				«IF hasExtendsDeclaration(type)»
					«getMappedDatatype(getExtendedType(type))»::operator==(other);
				«ELSE»
					true;
				«ENDIF»
		}

		bool «typeName»::operator!=(const «typeName»& other) const {
			return !(*this==other);
		}

		uint «typeName»::hashCode() const {
			«IF hasExtendsDeclaration(type)»
			uint hashCode = «getMappedDatatype(getExtendedType(type))»::hashCode();
			«ELSE»
			uint hashCode = 0;
			«ENDIF»
			«IF !getMembers(type).empty»
			int prime = 31;
			«ENDIF»
			«FOR member: getMembers(type)»
			hashCode = prime * hashCode + qHash(m_«member.joynrName»);
			«ENDFOR»
			return hashCode;
		}

		QString «typeName»::toString() const {
			QString result("«typeName»{");
			«IF hasExtendsDeclaration(type)»
				result += «getMappedDatatype(getExtendedType(type))»::toString();
				«IF !getMembers(type).empty»
				result += ", ";
				«ENDIF»
			«ENDIF»
			«FOR member: getMembers(type) SEPARATOR "\nresult += \", \";"»
				«val memberName = member.joynrName»
				«IF isArray(member)»
					result += " unprinted List «memberName»  ";
				«ELSEIF isEnum(member.type)»
					result += "«memberName»:" + get«memberName.toFirstUpper»Internal();
				«ELSEIF isComplex(member.type)»
					result += "«memberName»:" + get«memberName.toFirstUpper»().toString();
				«ELSE»
					result += "«memberName»:" + QVariant(get«memberName.toFirstUpper»()).toString();
				«ENDIF»
			«ENDFOR»
			result += "}";
			return result;
		}
		«getNamespaceEnder(type)»
		'''
	}
}
