package project

import (
	"fmt"
	"regexp"
	"strings"
)

const (
	maxProjectName = 64
	maxPackageName = 255
)

var (
	projectNamePattern = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$`)
	packagePartPattern = regexp.MustCompile(`^[A-Za-z_$][A-Za-z0-9_$]*$`)
	templatePattern    = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$`)

	javaKeywords = keywordSet(
		"abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while",
		"true false null _",
	)
)

// Spec is the canonical, validated input passed to the fixed wiz-spring CLI.
type Spec struct {
	ProjectName string
	PackageName string
	Template    string
}

// ValidationError identifies a user-controlled field that failed validation.
type ValidationError struct {
	Field   string
	Message string
}

func (e *ValidationError) Error() string {
	return e.Message
}

// Validate normalizes the package and template while keeping the project name
// deliberately strict so it can only be used as one path component.
func Validate(projectName, packageName, template string) (Spec, error) {
	if len(projectName) == 0 || len(projectName) > maxProjectName || !projectNamePattern.MatchString(projectName) {
		return Spec{}, &ValidationError{
			Field:   "projectName",
			Message: "projectName must be 1-64 lowercase ASCII characters, start and end with a letter or digit, and contain only letters, digits, '.', '_' or '-'",
		}
	}

	packageName = strings.TrimSpace(packageName)
	if packageName == "" || len(packageName) > maxPackageName {
		return Spec{}, &ValidationError{
			Field:   "packageName",
			Message: "packageName must be between 1 and 255 characters",
		}
	}
	if packageName == "java" || strings.HasPrefix(packageName, "java.") {
		return Spec{}, &ValidationError{
			Field:   "packageName",
			Message: "packageName must not use the java namespace",
		}
	}
	for _, part := range strings.Split(packageName, ".") {
		if !packagePartPattern.MatchString(part) {
			return Spec{}, &ValidationError{
				Field:   "packageName",
				Message: fmt.Sprintf("packageName segment %q is not a supported Java identifier", part),
			}
		}
		if _, reserved := javaKeywords[part]; reserved {
			return Spec{}, &ValidationError{
				Field:   "packageName",
				Message: fmt.Sprintf("packageName segment %q is reserved by Java 21", part),
			}
		}
	}

	template = strings.TrimSpace(template)
	if !templatePattern.MatchString(template) {
		return Spec{}, &ValidationError{
			Field:   "template",
			Message: "template must be a 1-64 character lowercase ASCII slug containing only letters, digits, '.', '_' or '-'",
		}
	}

	return Spec{ProjectName: projectName, PackageName: packageName, Template: template}, nil
}

func keywordSet(groups ...string) map[string]struct{} {
	result := make(map[string]struct{})
	for _, group := range groups {
		for _, keyword := range strings.Fields(group) {
			result[keyword] = struct{}{}
		}
	}
	return result
}
