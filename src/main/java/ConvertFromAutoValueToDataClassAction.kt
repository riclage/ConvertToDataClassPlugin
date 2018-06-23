import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitor
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.getValueParameterList
import org.jetbrains.kotlin.psi.psiUtil.isAbstract

class ConvertFromAutoValueToDataClassAction : AnAction("Convert from Abstract Class to Data Class") {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val file = event.getRequiredData(LangDataKeys.PSI_FILE)
        val classMap = mutableMapOf<KtClass, MutableList<KtNamedFunction>>()

        //Go through all abstract classes and their corresponding abstract methods in the file
        file.accept(object : KtTreeVisitor<Any>() {

            override fun visitClass(klass: KtClass, data: Any?): Void? {
                if (!klass.isData() && klass.isAbstract()) {
                    classMap[klass] = mutableListOf()
                }
                return super.visitClass(klass, data)
            }

            override fun visitNamedFunction(function: KtNamedFunction, data: Any?): Void? {
                val klass = function.containingClass()
                if (klass != null
                        && classMap.contains(function.containingClass())
                        && function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
                    classMap.getValue(klass).add(function)
                }
                return super.visitNamedFunction(function, data)
            }
        })

        val write = WriteCommandAction.writeCommandAction(project)
        write.run<Throwable> {
            //Convert each class to data class
            classMap.flatMap { (klass, functions) ->
                klass.addModifier(KtTokens.DATA_KEYWORD)
                klass.removeModifier(KtTokens.ABSTRACT_KEYWORD)

                return@flatMap functions.map { Pair(klass, it) }
            }
            //Convert each abstract function to a data class field
            .forEach { (klass, function) ->
                val oldName = function.name!!
                val newName = if (oldName.substring(0, 3) == "get") {
                    oldName
                } else {
                    "get${oldName.capitalize()}"
                }

                val factory = KtPsiFactory(klass)
                val param = factory.createParameter("val $oldName: ${function.typeReference?.text}")
                klass.getValueParameterList()?.addParameter(param)

                ReferencesSearch.search(function).findAll().forEach {
                    when (it.element.language) {
                        JavaLanguage.INSTANCE -> it.handleElementRename(newName)
                        KotlinLanguage.INSTANCE -> it.element.parent.replace(factory.createExpression(param.name!!))
                    }
                }
                function.delete()
            }
        }

        Messages.showMessageDialog(project, "Conversion to Data Class completed successfully", "Done!", Messages.getInformationIcon())
    }
}